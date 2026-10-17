package dev.keycorrect.ime

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.keycorrect.core.clipboard.ClipMeta
import dev.keycorrect.core.clipboard.ClipPolicy
import dev.keycorrect.core.clipboard.ClipText
import dev.keycorrect.core.clipboard.UrlCleaner
import dev.keycorrect.core.data.BlobTooLargeException
import dev.keycorrect.core.data.ClipKind
import dev.keycorrect.core.data.ClipboardLimits
import dev.keycorrect.core.data.ClipboardRepository
import dev.keycorrect.core.data.StoredClip
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.clipboard.ClipItem
import dev.keycorrect.feature.keyboard.clipboard.ClipboardEnvironment
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ClipboardEnv"

/** Runs [action] after a delay; the returned function cancels it. Main-thread in the app. */
fun interface DelayedRunner {
    fun post(delayMillis: Long, action: Runnable): () -> Unit
}

internal object MainThreadRunner : DelayedRunner {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun post(delayMillis: Long, action: Runnable): () -> Unit {
        handler.postDelayed(action, delayMillis)
        return { handler.removeCallbacks(action) }
    }
}

/**
 * Thumbnails are decoded one at a time, process-wide. The decrypted stream of a blob is buffered
 * whole by the AES-GCM provider (the tag is checked before any plaintext is released), so one
 * decode can hold up to the 20 MB budget twice over; a single lane keeps that to one image.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("InjectDispatcher") // The process-wide default; the environment takes it as a parameter.
internal val THUMBNAIL_DISPATCHER: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

/** Opens the decrypted bytes of an image; each call returns a new stream the caller closes. */
typealias ImageSource = suspend () -> InputStream

/** Puts a stored image clip into the focused field, or failing that on the system clipboard. */
fun interface ImagePaster {
    fun paste(clip: StoredClip): Boolean
}

/**
 * The keyboard's clipboard history (docs/PHASE4.md, PR C, simple history), shared by the whole
 * process. Everything copied is kept as is; the user deletes what they don't want. The only
 * protection goal is against other apps: the rows are in the encrypted database, image bytes in
 * Keystore-encrypted blobs, and images reach another app only through the non-exported
 * [ClipImageProvider].
 *
 * Memory ([clips]) is the keyboard's view: every member the keyboard calls only reads it. Changes
 * (pin, delete, clear, clean URL) update memory at once and are saved by one background queue, in
 * order; captured text goes through the same queue. After a queued job memory is refreshed from the
 * repository (also after a failed one; if even that fails, memory goes back to the last state
 * read), unless more jobs are waiting (they will refresh again), so a user action is never undone
 * by an older refresh. Copied images are read and encrypted outside the queue, on their own
 * coroutine with a timeout, so a content provider that hangs cannot hold up the queue; only the row
 * insert is queued. Clip contents are user data and are never logged (exception class names only).
 */
@Suppress(
    "TooManyFunctions", // One member per ClipboardEnvironment operation plus capture.
    "TooGenericExceptionCaught", // Background jobs must never take the process down.
    "LongParameterList", // Dispatchers, timer and decoder are injected so tests can control them.
)
class AppClipboardEnvironment(
    private val scope: CoroutineScope,
    private val repository: () -> ClipboardRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timer: DelayedRunner = MainThreadRunner,
    private val decodeThumbnail: suspend (ImageSource) -> ImageBitmap? = ThumbnailDecoder::decode,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val thumbnailDispatcher: CoroutineDispatcher = THUMBNAIL_DISPATCHER,
    private val captureDispatcher: CoroutineDispatcher = dispatcher,
    private val captureTimeoutMillis: Long = CAPTURE_TIMEOUT_MILLIS,
) : ClipboardEnvironment {
    private var current by mutableStateOf(KeyboardSettings())
    private var clipList by mutableStateOf(emptyList<ClipItem>())
    private var freshId by mutableStateOf<Long?>(null)
    private var thumbVersion by mutableIntStateOf(0)

    /** Guards [stored], [confirmed], [pending] and the publishing of [clipList]. */
    private val lock = Any()
    private var stored = emptyList<StoredClip>()

    /** The history as last read from the repository: where memory returns after a failed write. */
    private var confirmed = emptyList<StoredClip>()
    private var pending = 0

    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    @Volatile private var started = false
    @Volatile private var freshAt = 0L
    private var cancelFresh: (() -> Unit)? = null
    private val freshLock = Any()

    private val thumbLock = Any()
    private val thumbs =
        object : LinkedHashMap<Long, ImageBitmap>(THUMB_CACHE, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ImageBitmap>?) =
                size > THUMB_CACHE
        }
    private val thumbLoading = HashSet<Long>()
    private val thumbFailed = HashSet<Long>()

    /** Bumped (under [thumbLock]) by a reset: a load that began earlier must not fill the cache. */
    private var thumbGeneration = 0

    /** Set by the keyboard service: how an image clip reaches the field. Null: no keyboard. */
    @Volatile var imagePaster: ImagePaster? = null

    init {
        scope.launch(dispatcher) {
            for (job in queue) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Clipboard job failed: ${e.javaClass.name}")
                }
            }
        }
    }

    override val enabled: Boolean
        get() = current.clipboardHistory && !current.incognito

    override val clips: List<ClipItem>
        get() = clipList

    /** The settings changed. Tighter limits are applied to the stored history at once. */
    fun applySettings(settings: KeyboardSettings) {
        val old = current
        current = settings
        val limitsChanged =
            old.clipboardMaxItems != settings.clipboardMaxItems ||
                old.clipboardRetention != settings.clipboardRetention
        if (started && limitsChanged) submit { prune(it) }
    }

    /**
     * Loads the history (once per process; a failed load is retried at the next call) and applies
     * the retention rules. Later calls only re-apply the rules, so clips age out while the process
     * lives. Call on the main thread when the keyboard is shown.
     */
    fun start() {
        if (started) {
            submit { prune(it) }
            return
        }
        started = true
        submit { r ->
            try {
                r.orphanSweep()
                prune(r)
            } catch (e: Exception) {
                started = false
                throw e
            }
        }
    }

    /** The database was erased: forgets everything held in memory and reads back what is stored. */
    suspend fun reload() {
        synchronized(lock) {
            confirmed = emptyList()
            publish(emptyList())
        }
        clearFresh()
        resetThumbnails()
        val fresh = runCatching { repository().clips() }.getOrDefault(emptyList())
        synchronized(lock) {
            confirmed = fresh
            if (pending == 0) publish(fresh)
        }
    }

    // ------------------------------------------------------------------ capture

    /** Text was copied. Blank or very long (over [MAX_TEXT_CHARS]) text is not kept. */
    fun captureText(text: String) {
        if (!enabled || text.isBlank() || text.length > MAX_TEXT_CHARS) return
        submit { r ->
            markFresh(r.insertText(text))
            prune(r)
        }
    }

    /**
     * An image was copied. [open] is called off the queue, on its own coroutine, and the image is
     * read and encrypted there; if that takes longer than [CAPTURE_TIMEOUT_MILLIS] (a content
     * provider that hangs) the stream is closed and the image dropped. Images over the 20 MB budget
     * are not kept. Only the row insert uses the queue.
     */
    fun captureImage(mimeType: String, open: () -> InputStream?) {
        if (!enabled || !current.clipboardImages) return
        scope.launch(captureDispatcher) {
            try {
                val name = stageImage(open) ?: return@launch
                submit { r ->
                    markFresh(r.insertStaged(name, mimeType))
                    prune(r)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Image capture failed: ${e.javaClass.name}")
            }
        }
    }

    /**
     * Reads and encrypts the image from [open] with a time limit; returns the staged blob's name,
     * or null when there is no image, it is too big, or the limit passed. The reading runs on its
     * own coroutine so that this one can give up while a read is blocked: on timeout the stream is
     * closed (which wakes a blocked read) and the reader is cancelled and interrupted.
     */
    private suspend fun stageImage(open: () -> InputStream?): String? {
        val repo = repository()
        val result = CompletableDeferred<String?>()
        val stream = AtomicReference<InputStream?>()
        val reader =
            scope.launch(captureDispatcher) {
                var name: String? = null
                try {
                    var opened: InputStream? = null
                    val input =
                        try {
                            runInterruptible { open() }.also { opened = it }
                        } catch (e: CancellationException) {
                            // open() may still return after the timeout: don't leak the descriptor.
                            runCatching { opened?.close() }
                            throw e
                        }
                    stream.set(input)
                    name = input?.use {
                        try {
                            repo.stageImage(it, ClipPolicy.DEFAULT_IMAGE_BUDGET_BYTES)
                        } catch (_: BlobTooLargeException) {
                            null
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Image read failed: ${e.javaClass.name}")
                } finally {
                    // Too late (the capture gave up): nobody will insert this image.
                    if (!result.complete(name) && name != null) repo.discardStaged(name)
                }
            }
        val name = withTimeoutOrNull(captureTimeoutMillis) { result.await() }
        if (name == null && result.complete(null)) {
            runCatching { stream.get()?.close() }
            reader.cancel()
            Log.w(TAG, "Image capture timed out")
        }
        return name ?: result.await()
    }

    private suspend fun prune(r: ClipboardRepository) {
        val s = current
        val metas =
            r.clips().map {
                ClipMeta(
                    it.id,
                    it.createdAt,
                    it.pinned,
                    if (it.kind == ClipKind.Image) it.imageBytes else 0,
                )
            }
        val doomed =
            ClipPolicy.idsToDelete(
                metas,
                clock(),
                s.clipboardMaxItems,
                s.clipboardRetention.millis,
                ClipPolicy.DEFAULT_IMAGE_BUDGET_BYTES,
            )
        if (doomed.isNotEmpty()) r.delete(doomed)
        // The repository's own rule as a second guard (text count, image bytes).
        r.prune(ClipboardLimits(maxTextClips = s.clipboardMaxItems))
    }

    // ------------------------------------------------------------------ fresh clip

    private fun markFresh(id: Long) {
        synchronized(freshLock) {
            cancelFresh?.invoke()
            freshAt = clock()
            freshId = id
            cancelFresh = timer.post(FRESH_MILLIS) { if (freshId == id) freshId = null }
        }
    }

    private fun clearFresh() {
        synchronized(freshLock) {
            cancelFresh?.invoke()
            cancelFresh = null
            freshId = null
        }
    }

    override fun freshClip(): ClipItem? {
        val id = freshId
        val fresh = id != null && clock() - freshAt < FRESH_MILLIS
        return if (fresh) clipList.firstOrNull { it.id == id } else null
    }

    // ------------------------------------------------------------------ thumbnails

    override fun thumbnail(id: Long): ImageBitmap? {
        thumbVersion // read so drawing follows loads
        var cached: ImageBitmap? = null
        var loadGeneration = -1
        synchronized(thumbLock) {
            cached = thumbs[id]
            if (cached == null && id !in thumbLoading && id !in thumbFailed) {
                thumbLoading += id
                loadGeneration = thumbGeneration
            }
        }
        if (loadGeneration >= 0) {
            scope.launch(thumbnailDispatcher) { loadThumbnail(id, loadGeneration) }
        }
        return cached
    }

    private suspend fun loadThumbnail(id: Long, loadGeneration: Int) {
        val bitmap =
            try {
                val clip = synchronized(lock) { stored.firstOrNull { it.id == id } }
                val r = repository()
                // The decoder opens the stream twice (bounds, then a sampled decode).
                clip?.let { c -> decodeThumbnail { r.openImage(c) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A lost key, a damaged file or too big an image (OutOfMemoryError): the
                // thumbnail stays empty.
                Log.w(TAG, "Thumbnail failed: ${e.javaClass.name}")
                null
            }
        synchronized(thumbLock) {
            // Not if a reset or a delete happened meanwhile: the result is stale.
            if (loadGeneration == thumbGeneration && thumbLoading.remove(id)) {
                if (bitmap != null) thumbs[id] = bitmap else thumbFailed += id
            }
        }
        thumbVersion++
    }

    private fun resetThumbnails() {
        synchronized(thumbLock) {
            thumbs.clear()
            thumbFailed.clear()
            thumbLoading.clear()
            thumbGeneration++
        }
        thumbVersion++
    }

    // ------------------------------------------------------------------ user actions

    override fun paste(id: Long, commitText: (String) -> Unit): Boolean {
        val clip = synchronized(lock) { stored.firstOrNull { it.id == id } } ?: return false
        return when (clip.kind) {
            ClipKind.Text -> {
                clip.text?.let(commitText)
                clip.text != null
            }
            ClipKind.Image -> imagePaster?.paste(clip) ?: false
        }
    }

    override fun setPinned(id: Long, pinned: Boolean) {
        submit({ list -> list.map { if (it.id == id) it.copy(pinned = pinned) else it } }) {
            it.setPinned(id, pinned)
        }
    }

    override fun delete(id: Long) {
        forget(id)
        submit({ list -> list.filter { it.id != id } }) { it.delete(listOf(id)) }
    }

    override fun clearUnpinned() {
        synchronized(lock) { stored.filter { !it.pinned } }.forEach { forget(it.id) }
        submit({ list -> list.filter { it.pinned } }) { it.clear(keepPinned = true) }
    }

    /** "Clear clipboard history" in the settings: everything, pinned clips too. */
    fun clearAll() {
        clearFresh()
        resetThumbnails()
        submit({ emptyList() }) { it.clear(keepPinned = false) }
    }

    override fun cleanUrl(id: Long) {
        val clip = synchronized(lock) { stored.firstOrNull { it.id == id } } ?: return
        val cleaned = clip.text?.let(UrlCleaner::clean) ?: return
        submit({ list -> list.map { if (it.id == id) it.copy(text = cleaned) else it } }) {
            it.updateText(id, cleaned)
        }
    }

    private fun forget(id: Long) {
        if (freshId == id) clearFresh()
        synchronized(thumbLock) {
            thumbs.remove(id)
            thumbFailed.remove(id)
            thumbLoading.remove(id) // a load in flight is dropped when it finishes
        }
    }

    // ------------------------------------------------------------------ queue and memory

    /**
     * Applies [local] to memory now (if given), then runs [op] on the queue and refreshes memory
     * from the repository. If [op] (or getting the repository) fails, memory is still refreshed, or
     * returned to the last state read, so a change that was not saved does not stay on screen.
     */
    private fun submit(
        local: ((List<StoredClip>) -> List<StoredClip>)? = null,
        op: suspend (ClipboardRepository) -> Unit,
    ) {
        synchronized(lock) {
            if (local != null) publish(local(stored))
            pending++
        }
        val sent = queue.trySend { run(op) }
        if (sent.isFailure) settle(null, failed = true) // the queue is closed: nothing will run
    }

    private suspend fun run(op: suspend (ClipboardRepository) -> Unit) {
        var fresh: List<StoredClip>? = null
        var failed = true
        try {
            val r = repository()
            try {
                op(r)
                failed = false
            } finally {
                fresh = readOrNull(r)
            }
        } finally {
            settle(fresh, failed)
        }
    }

    private suspend fun readOrNull(r: ClipboardRepository): List<StoredClip>? =
        try {
            r.clips()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reading the history failed: ${e.javaClass.name}")
            null
        }

    /** Ends one job: memory follows the repository once no job is waiting. */
    private fun settle(fresh: List<StoredClip>?, failed: Boolean) {
        synchronized(lock) {
            pending--
            if (fresh != null) confirmed = fresh
            // Nothing read after a failure: the last state read is the best truth there is.
            if (pending == 0 && (fresh != null || failed)) publish(fresh ?: confirmed)
        }
    }

    private fun publish(list: List<StoredClip>) {
        stored = list
        clipList = list.sortedWith(ORDER).map(::toItem)
    }

    private fun toItem(clip: StoredClip): ClipItem {
        val text = clip.text
        return ClipItem(
            id = clip.id,
            kind = if (clip.kind == ClipKind.Image) ClipItem.Kind.Image else ClipItem.Kind.Text,
            preview = if (text == null) "" else ClipText.preview(text),
            createdAt = clip.createdAt,
            pinned = clip.pinned,
            cleanableUrl = text != null && UrlCleaner.clean(text) != null,
        )
    }

    companion object {
        /** Longer text is not kept (a partial paste would be worse than none). */
        const val MAX_TEXT_CHARS = 20_000

        /** How long the strip chip offers a new clip. */
        const val FRESH_MILLIS = 60_000L

        /** How long copying one image (reading and encrypting it) may take. */
        const val CAPTURE_TIMEOUT_MILLIS = 10_000L

        private const val THUMB_CACHE = 24
        private const val LOAD_FACTOR = 0.75f
        private val ORDER =
            compareByDescending<StoredClip> { it.pinned }
                .thenByDescending { it.createdAt }
                .thenByDescending { it.id }
    }
}

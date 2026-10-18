package dev.keycorrect.feature.voicesherpa

import dev.keycorrect.core.voice.SttError
import dev.keycorrect.core.voice.SttListener
import dev.keycorrect.core.voice.SttResult
import dev.keycorrect.core.voice.SttSession
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Voice-activity detection: turns a stream of audio into finished speech segments. The real one is
 * Silero VAD; tests use a fake. Used from the session's worker thread only.
 */
internal interface SpeechDetector {
    fun accept(samples: FloatArray)

    /** Closes the speech in progress, so [nextSegment] returns it too. */
    fun flush()

    /** The next finished speech segment, or null. */
    fun nextSegment(): FloatArray?

    fun release()
}

/** Offline recognition of one speech segment (16 kHz mono floats in -1..1). */
internal interface SegmentRecognizer {
    fun recognize(segment: FloatArray): String

    fun release()
}

internal class SpeechComponents(val detector: SpeechDetector, val recognizer: SegmentRecognizer)

/**
 * One dictation: audio goes through the VAD, every finished speech segment is recognised by the
 * offline model, and the results are emitted as [SttResult.Partial] (the text of all finished
 * segments so far, all of it stable) and, after [finish], one [SttResult.Final].
 *
 * Everything, including loading the models ([create]), happens in order on one worker thread, so
 * [acceptAudio] never blocks and the calling (audio) thread is not held up by decoding. Models are
 * freed when the session ends. Results go to [listener] from the worker thread; once [cancel] was
 * called nothing is emitted any more. No text or audio is ever logged.
 */
internal class SegmentedDictation(
    private val listener: SttListener,
    private val create: () -> SpeechComponents,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "keycorrect-stt")
    },
) : SttSession {
    // Worker-thread state.
    private var components: SpeechComponents? = null
    private val text = StringBuilder()
    private var failed = false

    private val pending = AtomicInteger()

    @Volatile private var cancelled = false
    @Volatile private var finished = false

    init {
        post { components = create() }
    }

    override fun acceptAudio(pcm: ShortArray, length: Int) {
        if (cancelled || finished || length <= 0) return
        val samples = FloatArray(length) { pcm[it] / PCM_SCALE }
        post {
            val c = components ?: return@post
            c.detector.accept(samples)
            drain(c)
        }
    }

    override val busy: Boolean
        get() = !cancelled && pending.get() > 0

    @Synchronized
    override fun finish() {
        if (cancelled || finished) return
        finished = true
        post {
            components?.let {
                it.detector.flush()
                drain(it)
            }
            emit(
                if (text.isNotEmpty()) SttResult.Final(text.toString())
                else SttResult.Error(SttError.NoMatch)
            )
        }
        // Queued tasks still run, then the worker frees the models and stops.
        post(always = true) { close() }
        executor.shutdown()
    }

    @Synchronized
    override fun cancel() {
        if (cancelled) return
        cancelled = true
        // The task in progress completes (native calls can't be interrupted); queued ones skip.
        if (!finished) {
            post(always = true) { close() }
            executor.shutdown()
        }
    }

    private fun drain(c: SpeechComponents) {
        while (!cancelled) {
            val segment = c.detector.nextSegment() ?: return
            val piece = c.recognizer.recognize(segment).trim()
            if (piece.isEmpty()) continue
            if (text.isNotEmpty()) text.append(' ')
            text.append(piece)
            emit(
                SttResult.Partial(
                    text.toString(),
                    stablePrefixLength = text.length,
                    endsSegment = true,
                )
            )
        }
    }

    private fun emit(result: SttResult) {
        if (!cancelled) listener.onResult(result)
    }

    private fun close() {
        components?.let {
            runCatching { it.detector.release() }
            runCatching { it.recognizer.release() }
        }
        components = null
    }

    /**
     * Runs [block] on the worker, unless the session was cancelled or failed ([always] tasks run
     * regardless: the clean-up). A failure ends the session with [SttError.Engine].
     */
    private fun post(always: Boolean = false, block: () -> Unit) {
        pending.incrementAndGet()
        try {
            executor.execute {
                try {
                    if (!always && (cancelled || failed)) return@execute
                    // Deliberately neither logged nor wrapped: a message could hold user text.
                    runCatching(block).onFailure {
                        failed = true
                        emit(SttResult.Error(SttError.Engine))
                        close()
                    }
                } finally {
                    pending.decrementAndGet()
                }
            }
        } catch (_: RejectedExecutionException) {
            // Cancelled or finished already.
            pending.decrementAndGet()
        }
    }

    private companion object {
        const val PCM_SCALE = 32768f
    }
}

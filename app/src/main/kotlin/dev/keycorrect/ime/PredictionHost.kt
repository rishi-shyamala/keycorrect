package dev.keycorrect.ime

import android.content.res.AssetManager
import android.util.Log
import dev.keycorrect.core.data.ShortcutMode
import dev.keycorrect.core.data.ShortcutSnapshot
import dev.keycorrect.core.data.ShortcutStore
import dev.keycorrect.core.data.UserDictionaryStore
import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.feature.keyboard.prediction.PredictionBackend
import dev.keycorrect.feature.keyboard.prediction.Predictor
import dev.keycorrect.feature.keyboard.prediction.ShortcutRule
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Connects the keyboard to the app's prediction data: the memory-mapped base dictionary, the user's
 * words and the text shortcuts. [start] loads everything off the main thread; until it is done (or
 * if the dictionary asset is missing or unreadable) [predictor] is null and the keyboard simply
 * offers no suggestions.
 *
 * The dictionary is mapped straight from the uncompressed asset, so it costs address space, not
 * heap. User words and shortcuts are read from the stores' in-memory snapshots, which are replaced
 * (never mutated) when the data changes, as [ClassicEngine] expects.
 */
class PredictionHost(
    private val assets: AssetManager,
    private val userDictionary: UserDictionaryStore,
    private val shortcutStore: ShortcutStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val assetPath: String = DICTIONARY_ASSET,
) : PredictionBackend {
    @Volatile
    override var predictor: Predictor? = null
        private set

    @Volatile private var dictionary: Dictionary? = null

    @Volatile private var started = false

    // The expansion map is rebuilt only when the store hands out a new snapshot.
    @Volatile private var cachedShortcutSource: ShortcutSnapshot? = null
    @Volatile private var cachedShortcuts: Map<String, String> = emptyMap()

    /**
     * Loads the stores and the dictionary in the background, in parallel (the dictionary needs no
     * database). Predictions start as soon as the dictionary is ready; the user's words and
     * shortcuts join the engine when their stores have loaded, as it reads their snapshots per use.
     * Safe to call more than once.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch(io) {
            val dictionaryLoad = async { loadDictionary() }
            launch {
                runCatching { userDictionary.load() }.onFailure { logFailure("user words", it) }
            }
            launch {
                runCatching { shortcutStore.load() }.onFailure { logFailure("shortcuts", it) }
            }
            val dict = dictionaryLoad.await() ?: return@launch
            dictionary = dict
            val engine =
                ClassicEngine(
                    dict,
                    userWords = { userDictionary.snapshot.words.keys },
                    shortcuts = ::shortcutExpansions,
                )
            engine.load()
            predictor = Predictor { request, stale -> engine.predictNow(request, stale) }
            // Build the contraction index here, off the main thread, rather than in the first
            // decode (which may be a separator decode on main). Predictions need not wait for it.
            runCatching { engine.warmUp() }.onFailure { logFailure("contraction index", it) }
        }
    }

    override fun shortcutFor(trigger: String): ShortcutRule? =
        shortcutStore.snapshot[trigger]?.let {
            ShortcutRule(it.expansion, it.mode == ShortcutMode.AUTO_EXPAND)
        }

    /**
     * Apostrophes are normalised (curly and modifier forms to `'`) and the user's words are
     * compared case-insensitively, so "Don’t" and "DON'T" are as known as "don't".
     */
    override fun isKnownWord(word: String): Boolean {
        val plain = normaliseApostrophes(word)
        val user = userDictionary.snapshot
        if (word in user || plain in user) return true
        if (user.words.keys.any { normaliseApostrophes(it).equals(plain, ignoreCase = true) }) {
            return true
        }
        val dict = dictionary ?: return true
        val key = plain.lowercase(Locale.ROOT)
        return dict.idsForKey(key).isNotEmpty() || dict.wordId(plain) >= 0
    }

    override fun addWord(word: String) {
        scope.launch(io) {
            runCatching { userDictionary.add(word) }.onFailure { logFailure("add word", it) }
        }
    }

    private fun normaliseApostrophes(word: String): String =
        word.replace('’', '\'').replace('‘', '\'').replace('ʼ', '\'')

    private fun shortcutExpansions(): Map<String, String> {
        val snapshot = shortcutStore.snapshot
        if (snapshot !== cachedShortcutSource) {
            cachedShortcuts = snapshot.shortcuts.mapValues { it.value.expansion }
            cachedShortcutSource = snapshot
        }
        return cachedShortcuts
    }

    /** Maps the asset read-only; null (logged) if it is absent, compressed or corrupt. */
    private fun loadDictionary(): Dictionary? =
        try {
            assets.openFd(assetPath).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    Dictionary.open(
                        stream.channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            fd.startOffset,
                            fd.length,
                        )
                    )
                }
            }
        } catch (e: java.io.FileNotFoundException) {
            Log.i(TAG, "No dictionary asset; suggestions are off")
            null
        } catch (e: Exception) {
            logFailure("dictionary", e)
            null
        }

    // Type only: messages could contain user text.
    private fun logFailure(what: String, e: Throwable) {
        Log.w(TAG, "Loading $what failed: ${e.javaClass.name}")
    }

    companion object {
        const val DICTIONARY_ASSET = "dictionaries/en_US.kcd"
        private const val TAG = "PredictionHost"
    }
}

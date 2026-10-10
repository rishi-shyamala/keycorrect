package dev.keycorrect.ime

import android.content.res.AssetManager
import android.util.Log
import dev.keycorrect.core.data.ShortcutMode
import dev.keycorrect.core.data.ShortcutSnapshot
import dev.keycorrect.core.data.ShortcutStore
import dev.keycorrect.core.data.UserDictionaryStore
import dev.keycorrect.core.data.UserWordSnapshot
import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.learning.Personalization
import dev.keycorrect.core.learning.model.RankingFeatureProvider
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
 *
 * Learning (docs/PLAN.md §6): with a [learning] host the engine personalises every decode with
 * `learning.personalization()` (a fresh immutable model snapshot per call: the engine caches its
 * spatial models per snapshot instance, so a stable view would never pick up learned touch
 * offsets), using [touchKey] to select the touch profile of the layout on screen. The host's model
 * also gets its ranking feature provider, which asks the engine for a candidate's features.
 *
 * Thread-safety of that provider: it runs on the learning thread only, and `featuresFor` takes the
 * same lock as typing decodes. So it is skipped (returns null; the model keeps the preference and
 * retries after a pause, see [LearningHost.onRankingDeferred]) while a typing decode is running or
 * ran within [DecodeActivity.QUIET_NANOS], and capped at [DecodeBudget.MAX_CALLS] decodes per
 * second. A keystroke can therefore wait for at most one learning decode, started when typing was
 * idle. Call [close] when the service goes away so the singleton model drops the engine.
 */
class PredictionHost(
    private val assets: AssetManager,
    private val userDictionary: UserDictionaryStore,
    private val shortcutStore: ShortcutStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val assetPath: String = DICTIONARY_ASSET,
    private val learning: LearningHost? = null,
    /** `InputProfile.touchKey` of the layout on screen; read on the decoder thread per decode. */
    private val touchKey: () -> String? = { null },
) : PredictionBackend {
    @Volatile
    override var predictor: Predictor? = null
        private set

    @Volatile private var dictionary: Dictionary? = null

    @Volatile private var started = false

    private val decodeActivity = DecodeActivity()
    private val decodeBudget = DecodeBudget()
    @Volatile private var installedProvider: RankingFeatureProvider? = null

    // User words folded for case/apostrophe-insensitive lookup, rebuilt when the store hands out a
    // new snapshot (rarely), so isKnownWord is a few hash lookups.
    private class FoldedWords(val source: UserWordSnapshot, val folded: Set<String>)

    @Volatile private var foldedUserWords: FoldedWords? = null

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
                    personalization =
                        learning?.let { host -> { host.personalization() } }
                            ?: {
                                Personalization.None
                            },
                    touchKey = touchKey,
                )
            engine.load()
            installRankingProvider(engine)
            predictor = Predictor { request, stale ->
                decodeActivity.track { engine.predictNow(request, stale) }
            }
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
        if (foldedWords(user).contains(plain.lowercase(Locale.ROOT))) return true
        if (learning?.model?.snapshot()?.isUserWord(plain) == true) return true
        val dict = dictionary ?: return true
        val key = plain.lowercase(Locale.ROOT)
        return dict.idsForKey(key).isNotEmpty() || dict.wordId(plain) >= 0
    }

    override fun addWord(word: String) {
        scope.launch(io) {
            runCatching { userDictionary.add(word) }.onFailure { logFailure("add word", it) }
        }
    }

    private fun foldedWords(snapshot: UserWordSnapshot): Set<String> {
        val cached = foldedUserWords
        if (cached != null && cached.source === snapshot) return cached.folded
        val folded =
            snapshot.words.keys.mapTo(HashSet()) { normaliseApostrophes(it).lowercase(Locale.ROOT) }
        foldedUserWords = FoldedWords(snapshot, folded)
        return folded
    }

    private fun installRankingProvider(engine: ClassicEngine) {
        val host = learning ?: return
        val bridge =
            EngineRankingFeatures(
                engine,
                decodeActivity,
                decodeBudget,
                field = { host.rankingField },
                onDeferred = host::onRankingDeferred,
                onFailure = { logFailure("ranking features", it) },
            )
        val provider: RankingFeatureProvider = { typed, context, candidate ->
            bridge.features(typed, context, candidate)
        }
        installedProvider = provider
        host.model.rankingFeatureProvider = provider
    }

    /** Detaches from the singleton learning model; call when the owning service is destroyed. */
    fun close() {
        val host = learning ?: return
        if (host.model.rankingFeatureProvider === installedProvider) {
            host.model.rankingFeatureProvider = null
        }
        installedProvider = null
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

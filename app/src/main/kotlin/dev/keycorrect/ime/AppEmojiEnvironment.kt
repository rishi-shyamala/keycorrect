package dev.keycorrect.ime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keycorrect.core.data.EmojiHistoryStore
import dev.keycorrect.core.emoji.EmojiCatalog
import dev.keycorrect.feature.keyboard.emoji.EmojiEnvironment
import dev.keycorrect.feature.keyboard.emoji.EmojiGlyphFilter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The keyboard's emoji data (docs/PHASE4.md, PR B), shared by the whole process. [start] loads the
 * catalog, hides the emoji the font cannot draw ([hasGlyph]) and reads the recents and variant
 * choices from [history], all on [dispatcher]; until then [catalog] is null. Every member the
 * keyboard calls only reads memory. Picks and choices update memory at once and are saved in the
 * background through [history], which holds the privacy gate; memory itself is updated only while
 * [recording] (learning on, incognito off), so an incognito pick leaves no trace anywhere. Emoji
 * values are user data and are never logged.
 */
class AppEmojiEnvironment(
    private val scope: CoroutineScope,
    private val history: () -> EmojiHistoryStore,
    private val loadCatalog: () -> EmojiCatalog,
    private val hasGlyph: (String) -> Boolean,
    private val recording: () -> Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EmojiEnvironment {
    private var loaded by mutableStateOf<EmojiCatalog?>(null)
    private var recentValues by mutableStateOf(emptyList<String>())
    private var variantsVersion by mutableIntStateOf(0)
    private val variants = ConcurrentHashMap<String, String>()
    @Volatile private var started = false

    /** Bumped by [reloadHistory]: a load that began before it must not merge stale history back. */
    @Volatile private var generation = 0

    /**
     * Guards read-modify-writes of [recentValues] (picks on the main thread, loads in the
     * background).
     */
    private val recentsLock = Any()

    override val catalog: EmojiCatalog?
        get() = loaded

    override val recents: List<String>
        get() = recentValues

    /** Starts loading, once per process; later calls do nothing. Call on the main thread. */
    fun start() {
        if (started) return
        started = true
        scope.launch(dispatcher) {
            val loadGeneration = generation
            val store = runCatching(history).getOrNull()
            val saved = store?.let { runCatching { it.recents() }.getOrNull() }.orEmpty()
            // Picks made before the history arrived stay in front.
            synchronized(recentsLock) {
                if (generation == loadGeneration) {
                    recentValues =
                        (recentValues + saved).distinct().take(EmojiHistoryStore.MAX_RECENTS)
                }
            }
            val filtered = runCatching { EmojiGlyphFilter.filter(loadCatalog(), hasGlyph) }
            // A failed load is tried again the next time the keyboard is shown.
            if (filtered.isFailure) started = false
            filtered.getOrNull()?.let { catalog ->
                if (store != null && generation == loadGeneration) loadVariants(store, catalog)
                // Builds the search index here, off the main thread: otherwise the first
                // keystroke's strip (strongMatch) or the first search would build it.
                catalog.byValue("")
                loaded = catalog
            }
        }
    }

    private suspend fun loadVariants(store: EmojiHistoryStore, catalog: EmojiCatalog) {
        val saved = runCatching { store.preferredVariants() }.getOrDefault(emptyMap())
        for ((base, variant) in saved) {
            // Only choices for emoji still in the (glyph-filtered) catalog; a choice made while
            // loading wins over the saved one.
            if (catalog.byValue(base)?.value == base) variants.putIfAbsent(base, variant)
        }
        variantsVersion++
    }

    override fun preferredVariant(base: String): String? {
        variantsVersion // read so drawing follows a choice
        return variants[base]
    }

    override fun setPreferredVariant(base: String, variant: String?) {
        if (variant != null && !recording()) return
        if (variant == null) variants.remove(base) else variants[base] = variant
        variantsVersion++
        persist { it.setPreferredVariant(base, variant) }
    }

    override fun onEmojiUsed(value: String, base: String) {
        if (!recording()) return
        synchronized(recentsLock) {
            recentValues =
                (listOf(value) + recentValues.filter { it != value }).take(
                    EmojiHistoryStore.MAX_RECENTS
                )
        }
        persist { it.recordUse(value, base) }
    }

    /**
     * The history was reset, partly forgotten or erased in the settings: drops what memory holds
     * and reads back what the store still has, so the panel and strip stop showing it at once
     * rather than when the process ends.
     */
    suspend fun reloadHistory() {
        generation++
        val store = runCatching(history).getOrNull()
        val saved = store?.let { runCatching { it.recents() }.getOrNull() }.orEmpty()
        val choices = store?.let { runCatching { it.preferredVariants() }.getOrNull() }.orEmpty()
        synchronized(recentsLock) { recentValues = saved }
        variants.clear()
        variants.putAll(choices)
        variantsVersion++
    }

    private fun persist(write: suspend (EmojiHistoryStore) -> Unit) {
        scope.launch(dispatcher) { runCatching { write(history()) } }
    }
}

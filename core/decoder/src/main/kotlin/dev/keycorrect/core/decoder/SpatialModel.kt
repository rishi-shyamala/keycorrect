package dev.keycorrect.core.decoder

import dev.keycorrect.core.engineapi.KeyLayoutModel
import dev.keycorrect.core.engineapi.KeySpec
import dev.keycorrect.core.engineapi.TouchPoint
import java.text.Normalizer

/** Learned shift of one key's touch distribution (Phase 3), in pixels of the layout. */
data class KeyAdjustment(val dx: Float = 0f, val dy: Float = 0f, val sigmaScale: Float = 1f) {
    companion object {
        val None = KeyAdjustment()
    }
}

/** Hook for per-user touch calibration: where touches meant for [key] actually land. */
fun interface TouchCorrection {
    fun adjust(key: Char): KeyAdjustment

    companion object {
        /** No learned offsets. */
        val Identity = TouchCorrection { KeyAdjustment.None }
    }
}

/**
 * Learned per-key mean touch offset in key units (fractions of the key's width and height), e.g.
 * from `Personalization.touchOffset`; null where nothing is learned for [key].
 */
fun interface KeyUnitOffsets {
    fun offset(key: Char): Pair<Float, Float>?
}

/**
 * Per-key 2D Gaussian touch model for one layout (§4.4). Each key's mean is its centre plus the
 * learned offset ([correction] in pixels, [unitOffsets] in key sizes); sigma is a fraction of its
 * size. Likelihoods are relative: a touch at the centre scores 0, so word scores are comparable
 * regardless of word length.
 *
 * Lookups are case-insensitive and accented letters use the key of their base letter with an accent
 * penalty. Immutable after construction, hence shareable between threads.
 */
class SpatialModel(
    keys: List<KeySpec>,
    val config: SpatialConfig = SpatialConfig(),
    correction: TouchCorrection = TouchCorrection.Identity,
    unitOffsets: KeyUnitOffsets? = null,
) {
    private val slotCount: Int
    private val meanX: FloatArray
    private val meanY: FloatArray
    private val invSigmaX: FloatArray
    private val invSigmaY: FloatArray
    private val slots = IntArray(TABLE_SIZE) { -1 }
    private val bases = CharArray(TABLE_SIZE) { it.toChar() }
    private val accentPenalties = FloatArray(TABLE_SIZE)

    init {
        val byChar = LinkedHashMap<Char, KeySpec>()
        for (key in keys) byChar.putIfAbsent(fold(key.char), key)
        slotCount = byChar.size
        meanX = FloatArray(slotCount)
        meanY = FloatArray(slotCount)
        invSigmaX = FloatArray(slotCount)
        invSigmaY = FloatArray(slotCount)
        // Slot s is the key for slotChars[s]; looked up by scanning (a few dozen keys) rather than
        // a
        // HashMap<Char, Int>, which boxed most chars of the table below on every rebuild.
        val slotChars = CharArray(slotCount)
        var s = 0
        for ((c, key) in byChar) {
            val adj = correction.adjust(c)
            val unit = unitOffsets?.offset(c)
            meanX[s] = key.centerX + adj.dx + (unit?.first ?: 0f) * key.width
            meanY[s] = key.centerY + adj.dy + (unit?.second ?: 0f) * key.height
            invSigmaX[s] =
                1f / (config.sigmaXFactor * key.width * adj.sigmaScale).coerceAtLeast(EPS)
            invSigmaY[s] =
                1f / (config.sigmaYFactor * key.height * adj.sigmaScale).coerceAtLeast(EPS)
            slotChars[s++] = c
        }
        for (code in 0 until TABLE_SIZE) {
            val lower = Character.toLowerCase(code.toChar())
            val base = BASES[code]
            bases[code] = base
            val own = slotChars.indexOf(lower)
            if (own >= 0) {
                slots[code] = own
            } else {
                val baseSlot = slotChars.indexOf(base)
                if (baseSlot >= 0) {
                    slots[code] = baseSlot
                    if (base != lower) accentPenalties[code] = config.accentPenalty
                }
            }
        }
    }

    /** Number of distinct keys. */
    val keyCount: Int
        get() = slotCount

    /** True if [char] (any case, accents folded) has a key. */
    fun hasKey(char: Char): Boolean = slotOf(char) >= 0

    /**
     * Log-likelihood of [char] given [touch], at most 0 and at least
     * [SpatialConfig.floorLogLikelihood]. Chars without a key and null touches carry no spatial
     * information and score 0 here; the decoder compares them as chars instead.
     */
    fun logLikelihood(char: Char, touch: TouchPoint?): Float {
        if (touch == null) return 0f
        val slot = slotOf(char)
        if (slot < 0) return 0f
        return gaussian(slot, touch.x, touch.y) + accentPenalty(char)
    }

    /** Key slot of [c] (any case; accented letters map to their base key) or -1. */
    internal fun slotOf(c: Char): Int = if (c.code < TABLE_SIZE) slots[c.code] else -1

    /** Lowercase base letter of [c] (accent stripped); [c] itself outside the tables. */
    internal fun baseOf(c: Char): Char = if (c.code < TABLE_SIZE) bases[c.code] else c

    internal fun accentPenalty(c: Char): Float =
        if (c.code < TABLE_SIZE) accentPenalties[c.code] else 0f

    /** Relative log-density of a touch at ([x], [y]) for key [slot], floored. */
    internal fun gaussian(slot: Int, x: Float, y: Float): Float {
        val dx = (x - meanX[slot]) * invSigmaX[slot]
        val dy = (y - meanY[slot]) * invSigmaY[slot]
        return maxOf(-0.5f * (dx * dx + dy * dy), config.floorLogLikelihood)
    }

    companion object {
        /** Chars below this (Latin through Latin Extended-B) are pre-resolved. */
        private const val TABLE_SIZE = 0x250
        private const val EPS = 1e-3f

        /**
         * Lowercase, accent-stripped base letter of every char below [TABLE_SIZE]. Independent of
         * the layout, so it is computed once rather than on every rebuild (Normalizer allocates).
         */
        private val BASES =
            CharArray(TABLE_SIZE) { stripAccent(Character.toLowerCase(it.toChar())) }

        /** Lowercases and maps typographic apostrophes to `'`. */
        internal fun fold(c: Char): Char =
            when (c) {
                '’',
                'ʼ' -> '\''
                else -> Character.toLowerCase(c)
            }

        private fun stripAccent(lower: Char): Char {
            if (lower.code < 0x80) return lower
            val decomposed = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)
            val first = decomposed[0]
            return if (first.code < 0x80 && first.isLetter()) first else lower
        }
    }
}

/**
 * Small cache of [SpatialModel]s keyed by [KeyLayoutModel.id] (portrait/landscape, layers), so the
 * Gaussians are rebuilt only when geometry or the touch correction changes.
 */
internal class SpatialModelCache(private val config: SpatialConfig, capacity: Int = 8) {
    /**
     * A model with the owner it was last checked against and the learned offsets it was built on.
     */
    private class Entry(
        val model: SpatialModel,
        val owner: Any?,
        val offsets: Array<Pair<Float, Float>?>,
    )

    private val models =
        object : LinkedHashMap<Long, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?) =
                size > capacity
        }
    private var correction: TouchCorrection? = null
    private var unitTag: String? = null

    /**
     * [unitOwner] and [unitTag] identify where [unitOffsets] come from (the personalization
     * snapshot and its touch profile). A new [unitOwner] is a new snapshot, which happens on every
     * learning publish while the learned touch offsets almost never change, so a cached model is
     * kept when the offsets it was built on are still the ones [unitOffsets] returns. A new
     * [unitTag] or [correction] rebuilds everything.
     */
    fun get(
        layout: KeyLayoutModel,
        correction: TouchCorrection,
        unitOffsets: KeyUnitOffsets? = null,
        unitOwner: Any? = null,
        unitTag: String? = null,
    ): SpatialModel {
        if (this.correction !== correction || this.unitTag != unitTag) {
            models.clear()
            this.correction = correction
            this.unitTag = unitTag
        }
        val cached = models[layout.id]
        if (cached != null && cached.owner === unitOwner) return cached.model
        val offsets = offsetsOf(layout.keys, unitOffsets)
        val model =
            if (cached != null && cached.offsets.contentEquals(offsets)) cached.model
            else SpatialModel(layout.keys, config, correction, unitOffsets)
        models[layout.id] = Entry(model, unitOwner, offsets)
        return model
    }

    /** The learned offset of each distinct key char, in the order [SpatialModel] asks for them. */
    private fun offsetsOf(
        keys: List<KeySpec>,
        unitOffsets: KeyUnitOffsets?,
    ): Array<Pair<Float, Float>?> {
        if (unitOffsets == null) return NO_OFFSETS
        val seen = LinkedHashSet<Char>()
        for (key in keys) seen.add(SpatialModel.fold(key.char))
        val out = arrayOfNulls<Pair<Float, Float>>(seen.size)
        var i = 0
        var any = false
        for (c in seen) {
            val o = unitOffsets.offset(c)
            out[i++] = o
            any = any || o != null
        }
        // Nothing learned builds the same model as no offsets at all.
        return if (any) out else NO_OFFSETS
    }

    private companion object {
        val NO_OFFSETS = emptyArray<Pair<Float, Float>?>()
    }
}

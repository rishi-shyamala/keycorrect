package dev.keycorrect.core.learning.model

import dev.keycorrect.core.learning.TouchSample
import kotlin.math.sqrt

/**
 * Per-key touch statistics of one key: decayed weighted sums (equivalent to a decayed Welford
 * accumulator, but linear so journaled samples can be subtracted exactly for "forget since T"). [n]
 * is the raw sample count used for the evidence threshold.
 */
internal data class TouchCell(
    val n: Int,
    val w: Double,
    val sx: Double,
    val sy: Double,
    val sxx: Double,
    val syy: Double,
    val last: Long,
) {
    val meanX: Double
        get() = if (w > 0) sx / w else 0.0

    val meanY: Double
        get() = if (w > 0) sy / w else 0.0

    val varX: Double
        get() = if (w > 0) (sxx / w - meanX * meanX).coerceAtLeast(0.0) else 0.0

    val varY: Double
        get() = if (w > 0) (syy / w - meanY * meanY).coerceAtLeast(0.0) else 0.0
}

/** Per (touchKey, char) learned touch offset; single-writer. Samples are in key-size units. */
internal class TouchModel(private val cfg: PersonalModelConfig) {
    private val decay = Decay(cfg.touchHalfLifeMillis.toDouble())
    private val profiles = HashMap<String, HashMap<Char, TouchCell>>()
    private val words = HashMap<String, Int>()
    private var dirty = true
    private var cached: FrozenTouch? = null

    private fun clean(s: TouchSample): Pair<Double, Double>? {
        val dx = s.dx
        val dy = s.dy
        if (dx.isNaN() || dy.isNaN() || dx.isInfinite() || dy.isInfinite()) return null
        val m = cfg.touchSampleClamp
        return dx.coerceIn(-m, m).toDouble() to dy.coerceIn(-m, m).toDouble()
    }

    private fun scaled(c: TouchCell, now: Long): TouchCell {
        val f = decay.factor(now - c.last)
        return TouchCell(c.n, c.w * f, c.sx * f, c.sy * f, c.sxx * f, c.syy * f, now)
    }

    fun add(touchKey: String, samples: List<TouchSample>, now: Long) {
        val map = profiles.getOrPut(touchKey) { HashMap() }
        var any = false
        for (s in samples) {
            val (dx, dy) = clean(s) ?: continue
            val ch = s.char.lowercaseChar()
            var c = map[ch]?.let { scaled(it, now) } ?: TouchCell(0, 0.0, 0.0, 0.0, 0.0, 0.0, now)
            c =
                TouchCell(
                    c.n + 1,
                    c.w + 1,
                    c.sx + dx,
                    c.sy + dy,
                    c.sxx + dx * dx,
                    c.syy + dy * dy,
                    now,
                )
            if (c.w > cfg.touchMaxWeight) {
                val f = cfg.touchMaxWeight / c.w
                c = TouchCell(c.n, c.w * f, c.sx * f, c.sy * f, c.sxx * f, c.syy * f, now)
            }
            map[ch] = c
            any = true
        }
        if (any) {
            words[touchKey] = (words[touchKey] ?: 0) + 1
            dirty = true
        }
    }

    /** Subtracts a previously added word that is [ageMillis] old (forget since T). */
    fun remove(touchKey: String, samples: List<TouchSample>, ageMillis: Long, now: Long) {
        val map = profiles[touchKey] ?: return
        val f = decay.factor(ageMillis)
        for (s in samples) {
            val (dx, dy) = clean(s) ?: continue
            val ch = s.char.lowercaseChar()
            val c0 = map[ch] ?: continue
            val c = scaled(c0, now)
            val n = c.n - 1
            val w = c.w - f
            if (n <= 0 || w <= 1e-6) {
                map.remove(ch)
            } else {
                map[ch] =
                    TouchCell(
                        n,
                        w,
                        c.sx - dx * f,
                        c.sy - dy * f,
                        (c.sxx - dx * dx * f).coerceAtLeast(0.0),
                        (c.syy - dy * dy * f).coerceAtLeast(0.0),
                        now,
                    )
            }
        }
        val left = (words[touchKey] ?: 0) - 1
        if (left <= 0) words.remove(touchKey) else words[touchKey] = left
        if (map.isEmpty()) profiles.remove(touchKey)
        dirty = true
    }

    fun freeze(): FrozenTouch {
        val c = cached
        if (!dirty && c != null) return c
        val copy = HashMap<String, Map<Char, TouchCell>>(profiles.size * 2)
        for ((k, v) in profiles) copy[k] = HashMap(v)
        val f = FrozenTouch(copy, HashMap(words), cfg)
        cached = f
        dirty = false
        return f
    }

    fun exportRows(): Pair<List<TouchRow>, List<TouchProfileRow>> {
        val rows = ArrayList<TouchRow>()
        for ((k, m) in profiles) for ((ch, c) in m) {
            rows += TouchRow(k, ch.code, c.n, c.w, c.sx, c.sy, c.sxx, c.syy, c.last)
        }
        return rows.sortedWith(compareBy({ it.touchKey }, { it.char })) to
            words.map { TouchProfileRow(it.key, it.value) }.sortedBy { it.touchKey }
    }

    fun importRows(rows: List<TouchRow>, profileRows: List<TouchProfileRow>) {
        profiles.clear()
        words.clear()
        for (r in rows) {
            profiles.getOrPut(r.touchKey) { HashMap() }[r.char.toChar()] =
                TouchCell(r.samples, r.weight, r.sumX, r.sumY, r.sumXX, r.sumYY, r.lastActiveMillis)
        }
        for (p in profileRows) words[p.touchKey] = p.words
        dirty = true
    }
}

/** Immutable touch view: offsets for the decoder and aggregates for the keyboard-fit report. */
internal class FrozenTouch(
    private val profiles: Map<String, Map<Char, TouchCell>>,
    private val words: Map<String, Int>,
    private val cfg: PersonalModelConfig,
) {
    fun offset(touchKey: String, char: Char): Pair<Float, Float>? {
        val c = profiles[touchKey]?.get(char.lowercaseChar()) ?: return null
        if (c.n < cfg.touchMinSamples || c.w <= 0) return null
        val m = cfg.touchMaxOffset
        return c.meanX.toFloat().coerceIn(-m, m) to c.meanY.toFloat().coerceIn(-m, m)
    }

    fun statistics(): TouchStatistics {
        val out = ArrayList<ProfileTouchStatistics>()
        for ((key, map) in profiles.toSortedMap()) {
            val keys = ArrayList<KeyTouchStatistics>()
            var samples = 0
            var wSum = 0.0
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var syy = 0.0
            var withinX = 0.0
            var withinY = 0.0
            for ((ch, c) in map.toSortedMap()) {
                keys +=
                    KeyTouchStatistics(
                        ch,
                        c.n,
                        c.meanX.toFloat(),
                        c.meanY.toFloat(),
                        sqrt(c.varX).toFloat(),
                        sqrt(c.varY).toFloat(),
                    )
                samples += c.n
                wSum += c.w
                sx += c.sx
                sy += c.sy
                sxx += c.sxx
                syy += c.syy
                withinX += c.varX * c.w
                withinY += c.varY * c.w
            }
            val wc = words[key] ?: 0
            out +=
                ProfileTouchStatistics(
                    touchKey = key,
                    wordCount = wc,
                    sampleCount = samples,
                    keys = keys,
                    sigmaX = if (wSum > 0) sqrt(withinX / wSum).toFloat() else 0f,
                    sigmaY = if (wSum > 0) sqrt(withinY / wSum).toFloat() else 0f,
                    meanOffsetX = if (wSum > 0) (sx / wSum).toFloat() else 0f,
                    meanOffsetY = if (wSum > 0) (sy / wSum).toFloat() else 0f,
                    rmsOffsetX = if (wSum > 0) sqrt(sxx / wSum).toFloat() else 0f,
                    rmsOffsetY = if (wSum > 0) sqrt(syy / wSum).toFloat() else 0f,
                    hasEnoughData = wc >= cfg.touchMinProfileWords,
                )
        }
        return TouchStatistics(out)
    }
}

/**
 * Aggregates for the keyboard-fit report (§6.5): never contains text, only per-key statistics. All
 * offsets and sigmas are in key sizes (x in key widths, y in key heights), so a sigma of 0.3 means
 * touches scatter over about a third of the key.
 */
data class TouchStatistics(val profiles: List<ProfileTouchStatistics>) {
    fun profile(touchKey: String): ProfileTouchStatistics? = profiles.firstOrNull {
        it.touchKey == touchKey
    }
}

data class ProfileTouchStatistics(
    val touchKey: String,
    /** Accepted words that contributed touches. */
    val wordCount: Int,
    /** Total touch samples over all keys. */
    val sampleCount: Int,
    val keys: List<KeyTouchStatistics>,
    /** Pooled within-key standard deviation (spread around each key's own mean). */
    val sigmaX: Float,
    val sigmaY: Float,
    /** Mean offset over all touches (systematic bias). */
    val meanOffsetX: Float,
    val meanOffsetY: Float,
    /** RMS distance from key centres (bias and spread together). */
    val rmsOffsetX: Float,
    val rmsOffsetY: Float,
    /** True once [wordCount] reaches the configured minimum for making recommendations. */
    val hasEnoughData: Boolean,
)

data class KeyTouchStatistics(
    val char: Char,
    val sampleCount: Int,
    val meanDx: Float,
    val meanDy: Float,
    val sigmaX: Float,
    val sigmaY: Float,
)

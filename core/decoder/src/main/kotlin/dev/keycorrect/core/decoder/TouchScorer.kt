package dev.keycorrect.core.decoder

import dev.keycorrect.core.engineapi.ComposingInput

/**
 * Scores dictionary chars against the typed chars and touches of one request. Likelihoods of every
 * key for every touch are computed once in [bind]; [ll] is then a table lookup, which keeps the
 * search loop cheap. Reused across requests, not thread-safe.
 *
 * A touch-less input char (popup, paste) matches only itself (accent/case folded); any other char
 * costs [SpatialConfig.nullMismatchPenalty], so it can only be reached through an edit.
 */
internal class TouchScorer(private val config: SpatialConfig, maxChars: Int) {
    private val typed = CharArray(maxChars)
    private val typedBase = CharArray(maxChars)
    private val hasTouch = BooleanArray(maxChars)
    private val exact = FloatArray(maxChars)
    private var table = FloatArray(0)
    private var model = SpatialModel(emptyList(), config)
    private var keyCount = 0

    /** Number of bound input chars. */
    var length = 0
        private set

    /** Binds [input] (at most the capacity given at construction) to [model]. */
    fun bind(model: SpatialModel, input: ComposingInput) {
        this.model = model
        keyCount = model.keyCount
        length = minOf(input.text.length, typed.size)
        if (table.size < length * keyCount) table = FloatArray(length * keyCount)
        for (pos in 0 until length) {
            val c = SpatialModel.fold(input.text[pos])
            typed[pos] = c
            typedBase[pos] = model.baseOf(c)
            val touch = input.touches[pos]
            hasTouch[pos] = touch != null
            if (touch != null) {
                val base = pos * keyCount
                for (k in 0 until keyCount) table[base + k] = model.gaussian(k, touch.x, touch.y)
            }
            val slot = model.slotOf(c)
            exact[pos] = if (touch != null && slot >= 0) table[pos * keyCount + slot] else 0f
        }
    }

    fun typedChar(pos: Int): Char = typed[pos]

    /** Log-likelihood of the typed char itself at [pos] (what the literal scores). */
    fun exactLogLikelihood(pos: Int): Float = exact[pos]

    /** True if [c] is what was typed at [pos], ignoring case and accents (not an edit). */
    fun sameLetter(c: Char, pos: Int): Boolean =
        c == typed[pos] || model.baseOf(c) == typedBase[pos]

    /** Log-likelihood that the user meant dictionary char [c] when producing input char [pos]. */
    fun ll(c: Char, pos: Int): Float {
        if (c == typed[pos]) return exact[pos]
        val slot = model.slotOf(c)
        if (model.baseOf(c) == typedBase[pos]) {
            val base = if (hasTouch[pos] && slot >= 0) table[pos * keyCount + slot] else 0f
            return maxOf(base + config.accentPenalty, config.floorLogLikelihood)
        }
        if (!hasTouch[pos]) return config.nullMismatchPenalty
        if (slot < 0) return config.floorLogLikelihood
        return maxOf(
            table[pos * keyCount + slot] + model.accentPenalty(c),
            config.floorLogLikelihood,
        )
    }

    /** Sum of [exactLogLikelihood] over the input. */
    fun literalLogLikelihood(): Float {
        var sum = 0f
        for (pos in 0 until length) sum += exact[pos]
        return sum
    }
}

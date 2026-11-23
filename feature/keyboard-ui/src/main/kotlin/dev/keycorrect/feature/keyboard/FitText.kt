package dev.keycorrect.feature.keyboard

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.unit.sp

/**
 * Draws [text] on one line at [base] size, shrunk continuously to no less than [min] when it does
 * not fit the width it is given, and ellipsized at the start if it still does not (see
 * [decideFit]). One measure pass and one draw, no composition of its own: the text is laid out in
 * the layout phase, where the width is known, instead of in a `BoxWithConstraints` (a
 * subcomposition) that picks the size for a `Text` to lay out again. Laid out text is cached per
 * width in the node, and by [measurer] across nodes.
 */
internal fun Modifier.fitText(
    text: String,
    style: TextStyle,
    color: Color,
    range: ShrinkRange,
    measurer: TextMeasurer,
): Modifier = this then FitTextElement(text, style, color, range, measurer)

private data class FitTextElement(
    val text: String,
    val style: TextStyle,
    val color: Color,
    val range: ShrinkRange,
    val measurer: TextMeasurer,
) : ModifierNodeElement<FitTextNode>() {
    override fun create() = FitTextNode(text, style, color, range, measurer)

    override fun update(node: FitTextNode) = node.update(text, style, color, range, measurer)
}

private class FitTextNode(
    private var text: String,
    private var style: TextStyle,
    private var color: Color,
    private var range: ShrinkRange,
    private var measurer: TextMeasurer,
) : Modifier.Node(), LayoutModifierNode, DrawModifierNode {
    private var layout: TextLayoutResult? = null
    private var layoutWidth = -1

    fun update(
        text: String,
        style: TextStyle,
        color: Color,
        range: ShrinkRange,
        measurer: TextMeasurer,
    ) {
        val remeasure =
            text != this.text ||
                style != this.style ||
                range != this.range ||
                measurer !== this.measurer
        this.text = text
        this.style = style
        this.range = range
        this.measurer = measurer
        val recolour = color != this.color
        this.color = color
        if (remeasure) {
            layout = null
            invalidateMeasurement()
            invalidateDraw()
        } else if (recolour) {
            invalidateDraw()
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val available = constraints.maxWidth
        val current = layout
        val result =
            if (current != null && layoutWidth == available) current
            else
                fit(available).also {
                    layout = it
                    layoutWidth = available
                }
        val size = constraints.constrain(result.size)
        // The wrapped node must be measured and placed: an unplaced child is never drawn, and this
        // node's draw runs as part of it.
        val placeable = measurable.measure(Constraints.fixed(size.width, size.height))
        return layout(size.width, size.height) { placeable.place(0, 0) }
    }

    /**
     * Full size if it fits [available] px, else shrunk down to the minimum, else cut at the start.
     */
    private fun fit(available: Int): TextLayoutResult {
        val natural = measureAt(text, range.base.value, Constraints())
        return if (available == Constraints.Infinity) natural else fitTo(natural, available)
    }

    private fun fitTo(natural: TextLayoutResult, available: Int): TextLayoutResult {
        val limit = Constraints(maxWidth = available)
        val decision =
            decideFit(
                base = range.base.value,
                min = range.min.value,
                available = available.toFloat(),
                widthAtBase = natural.size.width.toFloat(),
                widthAtMin = {
                    measureAt(text, minOf(range.min.value, range.base.value), Constraints())
                        .size
                        .width
                        .toFloat()
                },
            )
        var size = decision.size
        var result = if (decision.ellipsizeStart) null else measureAt(text, size, limit)
        // Glyph rounding can leave the estimate a pixel wide; step down until it fits.
        while (result != null && result.didOverflowWidth && size > decision.floor) {
            size = maxOf(decision.floor, size * RETRY_FACTOR)
            result = measureAt(text, size, limit)
        }
        return if (result == null || result.didOverflowWidth) cutAtStart(decision.floor, limit)
        else result
    }

    /** [text] with the front replaced by an ellipsis, as much of its end as fits at [size]. */
    private fun cutAtStart(size: Float, limit: Constraints): TextLayoutResult {
        val keep =
            largestFitting(text.length - 1) {
                !measureAt(startEllipsized(text, it), size, limit).didOverflowWidth
            }
        return measureAt(startEllipsized(text, keep), size, limit)
    }

    private fun measureAt(content: String, size: Float, limit: Constraints) =
        measurer.measure(
            content,
            style.copy(fontSize = size.sp),
            TextOverflow.Clip,
            softWrap = false,
            maxLines = 1,
            constraints = limit,
        )

    override fun ContentDrawScope.draw() {
        layout?.let { drawText(it, color = color) }
    }
}

/** The sizes in sp a word is drawn at: [base], shrunk as far as [min] to fit its slot. */
@Immutable internal data class ShrinkRange(val base: TextUnit, val min: TextUnit)

private const val RETRY_FACTOR = 0.97f

/**
 * The largest count in 1..[max] for which [fits] holds, by binary search (about log2([max]) calls),
 * assuming that fitting is monotone: fewer characters never fit worse. 1 when none fits.
 */
internal fun largestFitting(max: Int, fits: (Int) -> Boolean): Int {
    var lo = 1
    var hi = maxOf(1, max)
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (fits(mid)) lo = mid else hi = mid - 1
    }
    return lo
}

/** How a word is drawn in its slot: at [size] sp, with the front cut off if [ellipsizeStart]. */
internal data class FitDecision(val size: Float, val floor: Float, val ellipsizeStart: Boolean)

/**
 * The font size for a word of [widthAtBase] px at size [base] in [available] px: [base] if it fits,
 * else the size that scales it to fit, but not below [min] (never above [base] either). If even
 * [widthAtMin] (asked for only then) does not fit, it is drawn at the minimum with its start
 * ellipsized, so words sharing a prefix still differ.
 */
internal fun decideFit(
    base: Float,
    min: Float,
    available: Float,
    widthAtBase: Float,
    widthAtMin: () -> Float,
): FitDecision {
    val floor = minOf(min, base)
    val scaled = if (widthAtBase <= available) base else base * available / widthAtBase
    return if (scaled >= floor) FitDecision(scaled, floor, false)
    else FitDecision(floor, floor, widthAtMin() > available)
}

/**
 * The last [keep] characters of [text] after an ellipsis, never starting inside a surrogate pair.
 */
internal fun startEllipsized(text: String, keep: Int): String {
    if (keep >= text.length) return text
    var start = (text.length - keep).coerceIn(0, text.length)
    if (start in 1 until text.length && text[start].isLowSurrogate()) start++
    return "…" + text.substring(start)
}

package dev.keycorrect.feature.keyboard

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
 * not fit the width it is given, and ellipsized at the start if it still does not (see [decideFit]). One measure pass and one draw, no composition of
 * its own: the text is laid out in the layout phase, where the width is known, instead of in a
 * `BoxWithConstraints` (a subcomposition) that picks the size for a `Text` to lay out again. Laid
 * out text is cached per width in the node, and by [measurer] across nodes.
 */
internal fun Modifier.fitText(
    text: String,
    style: TextStyle,
    color: Color,
    base: TextUnit,
    min: TextUnit,
    measurer: TextMeasurer,
): Modifier = this then FitTextElement(text, style, color, base, min, measurer)

private data class FitTextElement(
    val text: String,
    val style: TextStyle,
    val color: Color,
    val base: TextUnit,
    val min: TextUnit,
    val measurer: TextMeasurer,
) : ModifierNodeElement<FitTextNode>() {
    override fun create() = FitTextNode(text, style, color, base, min, measurer)

    override fun update(node: FitTextNode) = node.update(text, style, color, base, min, measurer)
}

private class FitTextNode(
    private var text: String,
    private var style: TextStyle,
    private var color: Color,
    private var base: TextUnit,
    private var min: TextUnit,
    private var measurer: TextMeasurer,
) : Modifier.Node(), LayoutModifierNode, DrawModifierNode {
    private var layout: TextLayoutResult? = null
    private var layoutWidth = -1

    fun update(
        text: String,
        style: TextStyle,
        color: Color,
        base: TextUnit,
        min: TextUnit,
        measurer: TextMeasurer,
    ) {
        val remeasure =
            text != this.text ||
                style != this.style ||
                base != this.base ||
                min != this.min ||
                measurer !== this.measurer
        this.text = text
        this.style = style
        this.base = base
        this.min = min
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

    /** Full size if it fits [available] px, else shrunk down to [min], else cut at the start. */
    private fun fit(available: Int): TextLayoutResult {
        if (available == Constraints.Infinity) return measureAt(text, base.value, Constraints())
        val limit = Constraints(maxWidth = available)
        val natural = measureAt(text, base.value, Constraints())
        val decision =
            decideFit(
                base = base.value,
                min = min.value,
                available = available.toFloat(),
                widthAtBase = natural.size.width.toFloat(),
                widthAtMin = { measureAt(text, minOf(min.value, base.value), Constraints()).size.width.toFloat() },
            )
        if (decision.ellipsizeStart) return cutAtStart(decision.size, limit)
        var size = decision.size
        var result = measureAt(text, size, limit)
        // Glyph rounding can leave the estimate a pixel wide; step down until it fits.
        while (result.didOverflowWidth && size > decision.floor) {
            size = maxOf(decision.floor, size * RETRY_FACTOR)
            result = measureAt(text, size, limit)
        }
        return if (result.didOverflowWidth) cutAtStart(decision.floor, limit) else result
    }

    /** [text] with the front replaced by an ellipsis, as much of its end as fits at [size]. */
    private fun cutAtStart(size: Float, limit: Constraints): TextLayoutResult {
        var keep = text.length - 1
        var result = measureAt(startEllipsized(text, keep), size, limit)
        // Estimate the count to keep from the width, then trim until it fits.
        val ratio = limit.maxWidth.toFloat() / maxOf(1, result.size.width)
        keep = minOf(keep, (keep * ratio).toInt())
        while (true) {
            result = measureAt(startEllipsized(text, keep), size, limit)
            if (!result.didOverflowWidth || keep <= 1) return result
            keep--
        }
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

private const val RETRY_FACTOR = 0.97f

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
    if (widthAtBase <= available) return FitDecision(base, floor, false)
    val scaled = base * available / widthAtBase
    if (scaled >= floor) return FitDecision(scaled, floor, false)
    return FitDecision(floor, floor, widthAtMin() > available)
}

/** The last [keep] characters of [text] after an ellipsis, never starting inside a surrogate pair. */
internal fun startEllipsized(text: String, keep: Int): String {
    if (keep >= text.length) return text
    var start = (text.length - keep).coerceAtLeast(0)
    if (start in 1 until text.length && text[start].isLowSurrogate()) start++
    return "…" + text.substring(start)
}

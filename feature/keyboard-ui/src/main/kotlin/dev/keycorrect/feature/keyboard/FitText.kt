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

/**
 * Draws [text] on one line at [base] size, shrunk to [shrunkSize] when it does not fit the width it
 * is given, and ellipsized if it still does not. One measure pass and one draw, no composition of
 * its own: the text is laid out in the layout phase, where the width is known, instead of in a
 * `BoxWithConstraints` (a subcomposition) that picks the size for a `Text` to lay out again. Laid
 * out text is cached per width in the node, and by [measurer] across nodes.
 */
internal fun Modifier.fitText(
    text: String,
    style: TextStyle,
    color: Color,
    base: TextUnit,
    measurer: TextMeasurer,
): Modifier = this then FitTextElement(text, style, color, base, measurer)

private data class FitTextElement(
    val text: String,
    val style: TextStyle,
    val color: Color,
    val base: TextUnit,
    val measurer: TextMeasurer,
) : ModifierNodeElement<FitTextNode>() {
    override fun create() = FitTextNode(text, style, color, base, measurer)

    override fun update(node: FitTextNode) = node.update(text, style, color, base, measurer)
}

private class FitTextNode(
    private var text: String,
    private var style: TextStyle,
    private var color: Color,
    private var base: TextUnit,
    private var measurer: TextMeasurer,
) : Modifier.Node(), LayoutModifierNode, DrawModifierNode {
    private var layout: TextLayoutResult? = null
    private var layoutWidth = -1

    fun update(
        text: String,
        style: TextStyle,
        color: Color,
        base: TextUnit,
        measurer: TextMeasurer,
    ) {
        val remeasure =
            text != this.text ||
                style != this.style ||
                base != this.base ||
                measurer !== this.measurer
        this.text = text
        this.style = style
        this.base = base
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

    /** Full size if it fits [available] px, else [shrunkSize]; ellipsized if that overflows too. */
    private fun fit(available: Int): TextLayoutResult {
        val limit =
            if (available == Constraints.Infinity) Constraints()
            else Constraints(maxWidth = available)
        val full = measureAt(base, limit)
        if (available == Constraints.Infinity || !full.didOverflowWidth) return full
        val shrunk = shrunkSize(base)
        return if (shrunk == base) full else measureAt(shrunk, limit)
    }

    private fun measureAt(size: TextUnit, limit: Constraints) =
        measurer.measure(
            text,
            style.copy(fontSize = size),
            TextOverflow.Ellipsis,
            softWrap = false,
            maxLines = 1,
            constraints = limit,
        )

    override fun ContentDrawScope.draw() {
        layout?.let { drawText(it, color = color) }
    }
}

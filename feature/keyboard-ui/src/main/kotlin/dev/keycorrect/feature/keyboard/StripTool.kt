package dev.keycorrect.feature.keyboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

/** The icon buttons of the suggestion strip's tool row. */
internal enum class StripTool(val atLeftEdge: Boolean) {
    /** Toggles the editing panel; highlighted while it is open. */
    Edit(atLeftEdge = true),

    /** Opens the emoji panel, as the emoji key does. Not in sensitive fields. */
    Emoji(atLeftEdge = true),

    /** Opens the settings app. */
    Settings(atLeftEdge = false),

    /** Opens the clipboard panel. Only when [KeyboardController.clipboardAvailable]. */
    Clipboard(atLeftEdge = false),
}

/**
 * The tools shown, in order (those with [StripTool.atLeftEdge] come first). Emoji and the clipboard
 * are hidden in [sensitive] fields (the emoji key is absent there and the clipboard is off), and
 * the clipboard whenever it is unavailable (history off).
 */
internal fun toolsFor(sensitive: Boolean, clipboardAvailable: Boolean): List<StripTool> =
    buildList {
        add(StripTool.Edit)
        if (!sensitive) add(StripTool.Emoji)
        add(StripTool.Settings)
        if (clipboardAvailable && !sensitive) add(StripTool.Clipboard)
    }

/**
 * Whether the tools sit small at the edges: the field has text ([fieldHasText]; they spread out
 * only in an empty field), or the strip has anything else to show: a strip model (suggestions or a
 * prompt), a word being composed, the selection strip or a fresh clip chip. Autofill chips count
 * too but are known to the caller only. When this is false the tools are full size and evenly
 * spaced across the strip.
 */
internal fun stripBusy(
    fieldHasText: Boolean,
    stripEmpty: Boolean,
    composing: Boolean,
    selectionStrip: Boolean,
    hasClip: Boolean,
): Boolean = fieldHasText || !stripEmpty || composing || selectionStrip || hasClip

/** Tools to pin to the left edge when busy; the rest go to the right. */
internal fun leftToolCount(tools: List<StripTool>): Int = tools.count { it.atLeftEdge }

/**
 * Where the [count] tools sit in a strip [totalWidth] px wide, with the first [leftCount] pinned
 * left and the rest right when busy, each [edgeWidth] px wide. [t] is the busy progress: 0 for the
 * evenly spaced idle slots, 1 for the edge slots, interpolated linearly between.
 */
internal class ToolSlots(
    val count: Int,
    val leftCount: Int,
    val totalWidth: Int,
    val edgeWidth: Int,
) {
    fun width(t: Float): Int =
        lerp(totalWidth.toFloat() / count, edgeWidth.toFloat(), t).roundToInt()

    fun x(index: Int, t: Float): Int {
        val idle = index * totalWidth.toFloat() / count
        val edge =
            if (index < leftCount) index * edgeWidth else totalWidth - (count - index) * edgeWidth
        return lerp(idle, edge.toFloat(), t).roundToInt()
    }
}

private const val TOOL_ANIMATION_MILLIS = 250
private val TOOL_ANIMATION = tween<Float>(TOOL_ANIMATION_MILLIS, easing = FastOutSlowInEasing)
private val EdgeToolWidth = 32.dp
private const val IDLE_ICON_DP = 24f
private const val BUSY_ICON_DP = 18f

/**
 * The strip: [StripTool] buttons and [content] between them. Idle, the tools are full size and
 * evenly spaced across the strip and [content] is invisible (it has nothing to show); busy, they
 * shrink and move to the edges (Edit and Emoji left, Settings and Clipboard right) around
 * [content], which fades in. A 250 ms animation moves each tool between its two slots.
 *
 * The busy flag (see [stripBusy]; [autofillActive] adds autofill chips) is read only by the
 * [snapshotFlow] that drives the animation, and the animated progress only by the layout and draw
 * phases, so typing never recomposes this: it recomposes only what [content] reads.
 */
@Composable
@Suppress("LongParameterList") // the controller, host callbacks, and the slot; one entry point
internal fun StripTools(
    controller: KeyboardController,
    onPress: () -> Unit,
    onOpenSettings: () -> Unit,
    autofillActive: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // The selection strip needs the whole width (unless autofill chips took its place).
    val tools =
        if (controller.selectionStripVisible && !autofillActive) emptyList() else controller.tools
    val progress = rememberBusyProgress(controller, autofillActive)
    val editSelected = controller.editToolSelected
    val leftCount = leftToolCount(tools)
    val edgePx = with(LocalDensity.current) { EdgeToolWidth.roundToPx() }
    Layout(
        content = {
            tools.forEach { tool ->
                ToolButton(
                    icon = tool.icon(),
                    description = tool.description(),
                    progress = progress,
                    selected = if (tool == StripTool.Edit) editSelected else null,
                ) {
                    onPress()
                    controller.onTool(tool, onOpenSettings)
                }
            }
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }) {
                content()
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val count = tools.size
        val t = progress.value
        val slots = ToolSlots(count, leftCount, width, edgePx)
        val toolWidth = if (count == 0) 0 else slots.width(t)
        // The content is always laid out at its busy size; only its alpha animates.
        val middle =
            measurables[count].measure(
                Constraints.fixed((width - count * edgePx).coerceAtLeast(0), height)
            )
        val buttons = Array(count) { measurables[it].measure(Constraints.fixed(toolWidth, height)) }
        layout(width, height) {
            middle.place(leftCount * edgePx, 0)
            buttons.forEachIndexed { i, p ->
                p.place(slots.x(i, t), 0)
            }
        }
    }
}

/**
 * The busy progress: 0 idle, 1 busy, animated whenever [stripBusy] (or [autofillActive]) flips.
 * Read the returned state in layout or draw only.
 */
@Composable
private fun rememberBusyProgress(
    controller: KeyboardController,
    autofillActive: Boolean,
): State<Float> {
    val autofill = rememberUpdatedState(autofillActive)
    val animatable =
        remember(controller) {
            val busy = Snapshot.withoutReadObservation { controller.stripBusy || autofill.value }
            Animatable(if (busy) 1f else 0f)
        }
    LaunchedEffect(animatable) {
        snapshotFlow { controller.stripBusy || autofill.value }
            .collect { busy -> animatable.animateTo(if (busy) 1f else 0f, TOOL_ANIMATION) }
    }
    return animatable.asState()
}

/**
 * One tool button, a full-height touch target that fills the width it is given. Its icon shrinks
 * from 24 dp to 18 dp as [progress] goes to busy. [selected] is null for a plain button, else
 * whether the tool is on (drawn in the primary colour and reported to accessibility).
 */
@Composable
private fun ToolButton(
    icon: ImageVector,
    description: String,
    progress: State<Float>,
    selected: Boolean?,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            // Never focusable: a focus highlight on the first tool (Edit) when a selection change
            // moves or drops the focus elsewhere in the strip looked like the tool being selected.
            // The selected look is the [selected] flag alone.
            Modifier.fillMaxHeight()
                .focusProperties { canFocus = false }
                .clickable(onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    if (selected != null) this.selected = selected
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint =
                if (selected == true) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.size(IDLE_ICON_DP.dp).graphicsLayer {
                    val scale = lerp(1f, BUSY_ICON_DP / IDLE_ICON_DP, progress.value)
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}

private fun StripTool.icon(): ImageVector =
    when (this) {
        StripTool.Edit -> ShortcutIcons.Editing
        StripTool.Emoji -> ShortcutIcons.Emoji
        StripTool.Clipboard -> ShortcutIcons.Clipboard
        StripTool.Settings -> ShortcutIcons.Settings
    }

@Composable
private fun StripTool.description(): String =
    stringResource(
        when (this) {
            StripTool.Edit -> R.string.shortcut_editing
            StripTool.Emoji -> R.string.shortcut_emoji
            StripTool.Clipboard -> R.string.shortcut_clipboard
            StripTool.Settings -> R.string.shortcut_settings
        }
    )

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import dev.keycorrect.core.designsystem.accentOnBackgroundColor
import dev.keycorrect.core.designsystem.hintOnBackgroundColor
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

    /** Shows or hides the on-screen keys. Only with a physical keyboard attached. */
    Keyboard(atLeftEdge = false),
}

/**
 * The tools shown, in order (those with [StripTool.atLeftEdge] come first). Emoji and the clipboard
 * are hidden in [sensitive] fields (the emoji key is absent there and the clipboard is off), and
 * the clipboard whenever it is unavailable (history off).
 */
internal fun toolsFor(
    sensitive: Boolean,
    clipboardAvailable: Boolean,
    keyboardToggle: Boolean = false,
): List<StripTool> = buildList {
    add(StripTool.Edit)
    if (!sensitive) add(StripTool.Emoji)
    add(StripTool.Settings)
    if (clipboardAvailable && !sensitive) add(StripTool.Clipboard)
    if (keyboardToggle) add(StripTool.Keyboard)
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

/** How the strip lays out the tools; see [stripToolsMode]. */
internal enum class StripToolsMode {
    /** Today's layout: tools at the edges around the content, or spread out when idle. */
    Full,

    /** Suggestions shown: one chevron button at the left edge, the content takes the rest. */
    Collapsed,

    /** The chevron was tapped: the tools fill the strip in place of the suggestions. */
    Revealed,
}

/**
 * Suggestions ([suggestionsShown]) collapse the tools into one chevron button so they get nearly
 * the whole width; tapping it ([revealed]) swaps them for the tools. Autofill chips, the selection
 * strip and an idle strip (not [suggestionsShown]) keep the [StripToolsMode.Full] layout.
 */
internal fun stripToolsMode(
    suggestionsShown: Boolean,
    autofillActive: Boolean,
    revealed: Boolean,
): StripToolsMode =
    when {
        !suggestionsShown || autofillActive -> StripToolsMode.Full
        revealed -> StripToolsMode.Revealed
        else -> StripToolsMode.Collapsed
    }

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
internal val TOOL_ANIMATION = tween<Float>(TOOL_ANIMATION_MILLIS, easing = FastOutSlowInEasing)
private val EdgeToolWidth = 32.dp

/** Wide enough for a 48 dp touch target (the strip is 40 dp tall, so that is its limit). */
private val CollapseButtonWidth = 48.dp
private const val IDLE_ICON_DP = 24f
private const val BUSY_ICON_DP = 18f

/**
 * The strip: [StripTool] buttons and [content] between them, in one of three [StripToolsMode]s.
 *
 * [StripToolsMode.Full]: idle, the tools are full size and evenly spaced across the strip and
 * [content] is invisible (it has nothing to show); busy, they shrink and move to the edges (Edit
 * and Emoji left, Settings and Clipboard right) around [content], which fades in.
 * [StripToolsMode.Collapsed]: the tools fold into a chevron button at the left edge and [content]
 * widens to fill the rest. [StripToolsMode.Revealed]: the tools fan out from the chevron to even
 * slots and [content] fades out. Every change between these animates over 250 ms (see
 * [CollapseSlots] for the geometry).
 *
 * The busy flag (see [stripBusy]; [autofillActive] adds autofill chips) is read only by the
 * [snapshotFlow] that drives its animation, and the animated progress values only by the layout and
 * draw phases. The mode is derived, so only its flips recompose this: typing recomposes only what
 * [content] reads.
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // one entry point; one layout of all three modes
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
    val mode = rememberToolsMode(controller, autofillActive)
    // A strip with no tools has nothing to fold: it keeps the Full layout.
    val shownMode = if (tools.isEmpty()) StripToolsMode.Full else mode
    val collapse = rememberModeProgress(shownMode != StripToolsMode.Full)
    val reveal = rememberModeProgress(shownMode == StripToolsMode.Revealed)
    // What can be tapped (and is reported to accessibility) follows the target mode, so nothing
    // invisible takes a tap.
    val toolsActive = shownMode != StripToolsMode.Collapsed
    val chevronActive = shownMode != StripToolsMode.Full
    val revealed = shownMode == StripToolsMode.Revealed
    val leftCount = leftToolCount(tools)
    val density = LocalDensity.current
    val edgePx = with(density) { EdgeToolWidth.roundToPx() }
    val chevronPx = with(density) { CollapseButtonWidth.roundToPx() }
    val iconProgress =
        remember(progress, collapse, reveal) {
            derivedIconProgress(progress, collapse, reveal)
        }
    Layout(
        content = {
            tools.forEach { tool ->
                ToolButton(
                    icon = tool.icon(),
                    description = tool.description(),
                    progress = iconProgress,
                    selected = controller.selectedState(tool),
                    enabled = toolsActive,
                    modifier =
                        Modifier.graphicsLayer {
                            alpha = CollapseSlots.toolAlpha(collapse.value, reveal.value)
                        },
                ) {
                    onPress()
                    controller.onTool(tool, onOpenSettings)
                }
            }
            Box(
                modifier =
                    Modifier.fillMaxSize().graphicsLayer {
                        alpha =
                            CollapseSlots.contentAlpha(progress.value, collapse.value, reveal.value)
                    }
            ) {
                content()
            }
            ChevronButton(revealed, chevronActive, collapse) {
                onPress()
                controller.toggleToolsRevealed()
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val count = tools.size
        val slots = CollapseSlots(ToolSlots(count, leftCount, width, edgePx), chevronPx)
        val t = progress.value
        val c = collapse.value
        val r = reveal.value
        val middle = measurables[count].measure(Constraints.fixed(slots.contentWidth(c), height))
        val buttons =
            Array(count) {
                measurables[it].measure(Constraints.fixed(slots.toolWidth(t, c, r), height))
            }
        val chevron = measurables[count + 1].measure(Constraints.fixed(chevronPx, height))
        layout(width, height) {
            // A part that is fully hidden is not placed, so it draws nothing and takes no input.
            if (c < 1f || r < 1f) middle.place(slots.contentX(c), 0)
            if (c < 1f || r > 0f) {
                buttons.forEachIndexed { i, p -> p.place(slots.toolX(i, t, c, r), 0) }
            }
            // Placed last, so it is on top of the tools folding into it.
            if (c > 0f) chevron.place(slots.chevronX(c), 0)
        }
    }
}

/** Progress 0 is the idle, full-size icon. */
internal val FullSizeIcons: State<Float> =
    object : State<Float> {
        override val value: Float = 0f
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
    // The flow below watches this one Boolean: a keystroke changes the strip, the composing flag
    // and so on, which re-runs the derivation (a few comparisons) but only a flip wakes the flow.
    val busyNow = remember(controller) { derivedStateOf { controller.stripBusy } }
    val animatable =
        remember(controller) {
            val busy = Snapshot.withoutReadObservation { busyNow.value || autofill.value }
            Animatable(if (busy) 1f else 0f)
        }
    LaunchedEffect(animatable) {
        snapshotFlow { busyNow.value || autofill.value }
            .collect { busy -> animatable.animateTo(if (busy) 1f else 0f, TOOL_ANIMATION) }
    }
    return animatable.asState()
}

/**
 * One tool button, a full-height touch target that fills the width it is given. Its icon shrinks
 * from 24 dp to 18 dp as [progress] goes to busy. A button that is not [enabled] is hidden: it
 * takes no taps and is not reported to accessibility. [selected] is null for a plain button, else
 * whether the tool is on (drawn in the primary colour and reported to accessibility).
 */
@Composable
@Suppress("LongParameterList") // icon, label, state, modifier and click: a button's own inputs
internal fun ToolButton(
    icon: ImageVector,
    description: String,
    progress: State<Float>,
    selected: Boolean?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    stateDescription: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            // Never focusable: a focus highlight on the first tool (Edit) when a selection change
            // moves or drops the focus elsewhere in the strip looked like the tool being selected.
            // The selected look is the [selected] flag alone.
            modifier
                .fillMaxHeight()
                .focusProperties { canFocus = false }
                .clickable(enabled = enabled, onClick = onClick)
                .clearAndSetSemantics {
                    // A hidden button (folded away mid-animation) says nothing.
                    if (!enabled) return@clearAndSetSemantics
                    contentDescription = description
                    role = Role.Button
                    if (selected != null) this.selected = selected
                    if (stateDescription != null) this.stateDescription = stateDescription
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected == true) accentOnBackgroundColor() else hintOnBackgroundColor(),
            modifier =
                Modifier.size(IDLE_ICON_DP.dp).graphicsLayer {
                    val scale = lerp(1f, BUSY_ICON_DP / IDLE_ICON_DP, progress.value)
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}

/** Null for a plain button, else whether the tool is on (Edit: its panel is open). */
private fun KeyboardController.selectedState(tool: StripTool): Boolean? =
    when (tool) {
        StripTool.Edit -> editToolSelected
        StripTool.Keyboard -> fullKeyboardShown
        else -> null
    }

private fun StripTool.icon(): ImageVector =
    when (this) {
        StripTool.Edit -> ShortcutIcons.Editing
        StripTool.Emoji -> ShortcutIcons.Emoji
        StripTool.Clipboard -> ShortcutIcons.Clipboard
        StripTool.Settings -> ShortcutIcons.Settings
        StripTool.Keyboard -> ShortcutIcons.Keyboard
    }

@Composable
private fun StripTool.description(): String =
    stringResource(
        when (this) {
            StripTool.Edit -> R.string.shortcut_editing
            StripTool.Emoji -> R.string.shortcut_emoji
            StripTool.Clipboard -> R.string.shortcut_clipboard
            StripTool.Settings -> R.string.shortcut_settings
            StripTool.Keyboard -> R.string.shortcut_keyboard
        }
    )

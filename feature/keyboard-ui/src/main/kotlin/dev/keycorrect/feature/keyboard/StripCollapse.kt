package dev.keycorrect.feature.keyboard

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/**
 * How long an emptied strip keeps the collapsed layout, waiting for suggestions to return: meant to
 * cover a next-word decode (a few ms on the desktop; tune from device numbers), and short enough
 * that a really empty strip gets its tools back soon.
 */
internal const val SUGGESTIONS_HOLD_MILLIS = 150L

/**
 * Where everything sits as the strip moves between its [StripToolsMode]s: [full] is the
 * [StripToolsMode.Full] layout, [chevronWidth] the collapse button's width. The progress values are
 * t (busy, as in [ToolSlots]), c (0 Full, 1 collapsed or revealed) and r (0 collapsed, 1 revealed).
 * Collapsed, every tool sits on the chevron, invisible; revealed, the tools share the width right
 * of the chevron evenly. Each value is a linear blend of those targets.
 */
internal class CollapseSlots(private val full: ToolSlots, private val chevronWidth: Int) {
    private val count = full.count
    private val rest = (full.totalWidth - chevronWidth).coerceAtLeast(0)

    /** The chevron slides in from beyond the left edge. */
    fun chevronX(c: Float): Int = lerp(-chevronWidth.toFloat(), 0f, c).roundToInt()

    fun contentX(c: Float): Int =
        lerp((full.leftCount * full.edgeWidth).toFloat(), chevronWidth.toFloat(), c).roundToInt()

    fun contentWidth(c: Float): Int {
        val fullWidth = (full.totalWidth - count * full.edgeWidth).coerceAtLeast(0)
        return lerp(fullWidth.toFloat(), rest.toFloat(), c).roundToInt()
    }

    fun toolWidth(t: Float, c: Float, r: Float): Int {
        if (count == 0) return 0
        val folded = lerp(chevronWidth.toFloat(), rest.toFloat() / count, r)
        return lerp(full.width(t).toFloat(), folded, c).roundToInt()
    }

    fun toolX(index: Int, t: Float, c: Float, r: Float): Int {
        val revealedX = chevronWidth + index * rest.toFloat() / count
        val folded = lerp(0f, revealedX, r)
        return lerp(full.x(index, t).toFloat(), folded, c).roundToInt()
    }

    companion object {
        /** Tools fade out as they fold into the chevron and back in as they fan out. */
        fun toolAlpha(c: Float, r: Float): Float = lerp(1f, r, c)

        /** Full: the busy fade; collapsed: shown; revealed: hidden behind the tools. */
        fun contentAlpha(t: Float, c: Float, r: Float): Float = lerp(t, 1f - r, c)

        /** The icons' size progress (0 full size, 1 busy size): revealed tools are full size. */
        fun iconProgress(t: Float, c: Float, r: Float): Float = lerp(t, 1f - r, c)
    }
}

internal fun derivedIconProgress(
    busy: State<Float>,
    collapse: State<Float>,
    reveal: State<Float>,
): State<Float> =
    object : State<Float> {
        override val value: Float
            get() = CollapseSlots.iconProgress(busy.value, collapse.value, reveal.value)
    }

/**
 * The [StripToolsMode] to show, derived so that only its flips recompose. Whether the strip has
 * suggestions is held ([holdFalse]) for [SUGGESTIONS_HOLD_MILLIS] when it empties: a committed word
 * empties the strip until its next-word predictions arrive, and without the hold the full tool row
 * flashed in that gap. Everything else (the prompt, the selection strip, autofill, a chevron tap)
 * applies at once.
 */
@Composable
internal fun rememberToolsMode(
    controller: KeyboardController,
    autofillActive: Boolean,
): StripToolsMode {
    val autofill = rememberUpdatedState(autofillActive)
    val held =
        remember(controller) {
            mutableStateOf(Snapshot.withoutReadObservation { controller.stripHasSuggestions })
        }
    LaunchedEffect(controller) {
        snapshotFlow { controller.stripHasSuggestions }
            .holdFalse(SUGGESTIONS_HOLD_MILLIS)
            .collect { held.value = it }
    }
    val mode by
        remember(controller) {
            derivedStateOf {
                stripToolsMode(
                    held.value && controller.suggestionsAllowed,
                    autofill.value,
                    controller.toolsRevealed,
                )
            }
        }
    return mode
}

/**
 * Passes true on at once, but false only once it has lasted [millis]; a true within that time
 * cancels it, so a brief false never shows.
 */
@OptIn(ExperimentalCoroutinesApi::class) // transformLatest: stable in practice since 1.3
internal fun Flow<Boolean>.holdFalse(millis: Long): Flow<Boolean> =
    distinctUntilChanged()
        .transformLatest {
            if (!it) delay(millis)
            emit(it)
        }
        .distinctUntilChanged()

/** 0 to 1 as [on] turns on, back as it turns off; starts at its first value. */
@Composable
internal fun rememberModeProgress(on: Boolean): State<Float> {
    val animatable = remember { Animatable(if (on) 1f else 0f) }
    LaunchedEffect(on) { animatable.animateTo(if (on) 1f else 0f, TOOL_ANIMATION) }
    return animatable.asState()
}

/**
 * The collapse button at the strip's left edge: a right chevron that reveals the tools, a left one
 * that hides them again. It fades in with [collapse] and takes taps only when [active].
 */
@Composable
internal fun ChevronButton(
    revealed: Boolean,
    active: Boolean,
    collapse: State<Float>,
    onClick: () -> Unit,
) {
    ToolButton(
        icon = if (revealed) ShortcutIcons.ChevronLeft else ShortcutIcons.ChevronRight,
        description =
            stringResource(
                if (revealed) R.string.shortcut_tools_hide else R.string.shortcut_tools_show
            ),
        progress = FullSizeIcons,
        selected = null,
        enabled = active,
        modifier = Modifier.graphicsLayer { alpha = collapse.value },
        stateDescription =
            stringResource(
                if (revealed) R.string.shortcut_tools_expanded
                else R.string.shortcut_tools_collapsed
            ),
        onClick = onClick,
    )
}

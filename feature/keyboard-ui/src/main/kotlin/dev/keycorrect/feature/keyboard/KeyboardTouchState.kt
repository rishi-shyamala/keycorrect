package dev.keycorrect.feature.keyboard

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import dev.keycorrect.core.layout.KeySlot
import dev.keycorrect.core.layout.ResolvedKey
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Bubble size relative to the key it previews. */
private const val PREVIEW_WIDTH_SCALE = 1.3f
private const val PREVIEW_HEIGHT_SCALE = 1.1f

/**
 * The long-press popup panel: [count] equal cells in a row, positioned in the key surface's
 * coordinate space.
 */
internal data class PopupPanel(
    val left: Float,
    val top: Float,
    val cellWidth: Float,
    val cellHeight: Float,
    val count: Int,
) {
    val width: Float
        get() = cellWidth * count

    /** The cell under [x], clamped to the panel so a pointer beyond either end selects the end. */
    fun indexAt(x: Float): Int = floor((x - left) / cellWidth).toInt().coerceIn(0, count - 1)

    companion object {
        /**
         * Places a panel of [count] cells above [slot]: cells are key-sized but at least
         * [minCellWidth] (narrow keys such as the bottom row's still get letter-sized cells), the
         * first one sits over the key, and the panel is shifted left as needed to stay inside
         * [surfaceWidth].
         */
        fun above(
            slot: KeySlot,
            count: Int,
            surfaceWidth: Float,
            minCellWidth: Float = 0f,
        ): PopupPanel {
            val cellHeight = slot.bottom - slot.top
            val cellWidth = min(max(slot.right - slot.left, minCellWidth), surfaceWidth / count)
            val left = slot.left.coerceIn(0f, max(0f, surfaceWidth - cellWidth * count))
            return PopupPanel(left, slot.top - cellHeight, cellWidth, cellHeight, count)
        }
    }
}

/**
 * True once the pointer has moved more than one row height below [slot], which cancels a popup. The
 * pointer going up or sideways never cancels.
 */
internal fun isFarBelow(y: Float, slot: KeySlot): Boolean =
    y > slot.bottom + (slot.bottom - slot.top)

/**
 * Where to draw the key preview for the key occupying [key] (overlay coordinates), inside an
 * overlay of size [bounds]: centred above the key, its bottom edge overlapping the key's top by
 * [overlap], and shifted down or sideways so it never leaves the overlay.
 */
internal fun previewBounds(key: Rect, overlap: Float, bounds: Size): Rect {
    val width = min(key.width * PREVIEW_WIDTH_SCALE, bounds.width)
    val height = key.height * PREVIEW_HEIGHT_SCALE
    val left = (key.center.x - width / 2f).coerceIn(0f, bounds.width - width)
    val top = max(key.top + overlap - height, 0f)
    return Rect(left, top, left + width, top + height)
}

/** An open long-press popup, owned by the pointer (id) that opened it. */
@Stable
internal class PopupState(
    val owner: Long,
    val slot: KeySlot,
    val panel: PopupPanel,
    val entries: List<ResolvedKey>,
    highlighted: Int,
) {
    /** The cell that a release would commit. */
    var highlighted by mutableIntStateOf(highlighted)
}

/**
 * Transient touch state shared by the key surface (which writes it from pointer events) and the
 * overlay (which only reads it while drawing). Everything here is read in draw phases, so changes
 * redraw without recomposing anything.
 */
@Stable
internal class KeyboardTouchState {
    /** Indices of the slots currently held down. */
    var pressed by mutableStateOf(emptySet<Int>())

    /** The key whose preview bubble is shown, if any. */
    var preview by mutableStateOf<KeySlot?>(null)

    var popup by mutableStateOf<PopupState?>(null)

    /**
     * The swipe path to draw as a trail while a swipe is in progress (null otherwise). The path
     * grows in place, so the overlay watches [trailVersion] to know to redraw.
     */
    var trail by mutableStateOf<SwipePath?>(null)

    /** Bumped on every point the trail gains. */
    var trailVersion by mutableIntStateOf(0)

    /** Window position of the key surface's top-left corner. */
    var surfaceOrigin by mutableStateOf(Offset.Zero)

    /** Window position of the overlay's top-left corner. */
    var overlayOrigin by mutableStateOf(Offset.Zero)

    /** Where the key surface sits inside the overlay. */
    val surfaceInOverlay: Offset
        get() = surfaceOrigin - overlayOrigin

    fun reset() {
        pressed = emptySet()
        preview = null
        popup = null
        trail = null
    }
}

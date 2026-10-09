package dev.keycorrect.feature.keyboard

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeySlot
import dev.keycorrect.core.layout.ResolvedKey
import org.junit.Test

class KeyOverlayGeometryTest {
    private fun slot(left: Float, top: Float = 100f, width: Float = 100f, height: Float = 50f) =
        KeySlot(0, ResolvedKey("a", KeyAction.Text("a")), left, top, left + width, top + height)

    @Test
    fun panelSitsAboveKeyWithFirstCellOverIt() {
        val panel = PopupPanel.above(slot(left = 300f), count = 4, surfaceWidth = 1000f)
        assertThat(panel.left).isEqualTo(300f)
        assertThat(panel.cellWidth).isEqualTo(100f)
        assertThat(panel.top).isEqualTo(50f) // one row above the key
        assertThat(panel.indexAt(350f)).isEqualTo(0)
    }

    @Test
    fun panelIsClampedInsideSurface() {
        val right = PopupPanel.above(slot(left = 900f), count = 4, surfaceWidth = 1000f)
        assertThat(right.left).isEqualTo(600f)
        assertThat(right.left + right.width).isEqualTo(1000f)
        val left = PopupPanel.above(slot(left = -10f), count = 2, surfaceWidth = 1000f)
        assertThat(left.left).isEqualTo(0f)
    }

    @Test
    fun cellsShrinkWhenPanelIsWiderThanSurface() {
        val panel = PopupPanel.above(slot(left = 0f), count = 20, surfaceWidth = 1000f)
        assertThat(panel.cellWidth).isEqualTo(50f)
        assertThat(panel.left).isEqualTo(0f)
        assertThat(panel.width).isEqualTo(1000f)
    }

    @Test
    fun indexFollowsPointerAndClampsAtEnds() {
        val panel = PopupPanel(left = 200f, top = 0f, cellWidth = 100f, cellHeight = 50f, count = 3)
        assertThat(panel.indexAt(199f)).isEqualTo(0)
        assertThat(panel.indexAt(-500f)).isEqualTo(0)
        assertThat(panel.indexAt(200f)).isEqualTo(0)
        assertThat(panel.indexAt(299.9f)).isEqualTo(0)
        assertThat(panel.indexAt(300f)).isEqualTo(1)
        assertThat(panel.indexAt(450f)).isEqualTo(2)
        assertThat(panel.indexAt(5_000f)).isEqualTo(2)
    }

    @Test
    fun farBelowMeansMoreThanOneRowUnderTheKey() {
        val s = slot(left = 0f, top = 100f, height = 50f) // bottom = 150
        assertThat(isFarBelow(150f, s)).isFalse()
        assertThat(isFarBelow(200f, s)).isFalse()
        assertThat(isFarBelow(200.1f, s)).isTrue()
        assertThat(isFarBelow(-100f, s)).isFalse()
    }

    @Test
    fun previewIsWiderAndTallerThanKeyAndOverlapsItsTop() {
        val key = Rect(400f, 200f, 500f, 250f)
        val r = previewBounds(key, overlap = 4f, bounds = Size(1000f, 600f))
        assertThat(r.width).isWithin(0.01f).of(130f)
        assertThat(r.height).isWithin(0.01f).of(55f)
        assertThat(r.center.x).isWithin(0.01f).of(450f)
        assertThat(r.bottom).isWithin(0.01f).of(204f)
    }

    @Test
    fun previewNeverLeavesTheOverlay() {
        val topRow = Rect(0f, 5f, 100f, 55f)
        val r = previewBounds(topRow, overlap = 4f, bounds = Size(1000f, 600f))
        assertThat(r.top).isEqualTo(0f)
        assertThat(r.left).isEqualTo(0f)
        val edge = previewBounds(Rect(900f, 100f, 1000f, 150f), 4f, Size(1000f, 600f))
        assertThat(edge.right).isAtMost(1000f)
    }

    @Test
    fun shiftLabelsAndDescriptionsFollowState() {
        val shift = ResolvedKey("⇧", KeyAction.Shift, description = "Shift")
        val a = ResolvedKey("a", KeyAction.Text("a", "A"), shiftedLabel = "A")
        assertThat(ShiftState.entries.map { keyLabel(shift, it, "↵") })
            .containsExactly("⇧", "⬆", "⬆", "⇪")
            .inOrder()
        assertThat(keyLabel(a, ShiftState.Off, "↵")).isEqualTo("a")
        assertThat(keyLabel(a, ShiftState.Auto, "↵")).isEqualTo("A")
        assertThat(keyLabel(ResolvedKey("↵", KeyAction.Enter), ShiftState.Off, "Go"))
            .isEqualTo("Go")
        assertThat(keyDescription(shift, ShiftState.Off)).isEqualTo("Shift")
        assertThat(keyDescription(shift, ShiftState.Once)).isEqualTo("Shift, on")
        assertThat(keyDescription(shift, ShiftState.Locked)).isEqualTo("Caps lock on")
        assertThat(keyDescription(a, null)).isEqualTo("a")
    }
}

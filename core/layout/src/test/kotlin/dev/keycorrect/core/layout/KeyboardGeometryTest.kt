package dev.keycorrect.core.layout

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeyboardGeometryTest {
    private val letters =
        KeyboardComposer(BuiltInLayouts.repository()).compose(KeyboardSet.Default, "letters")
    private val geometry = KeyboardGeometry(letters, width = 1000f, height = 400f)

    private fun labelAt(x: Float, y: Float) = geometry.hitTest(x, y)?.key?.label

    private fun key(label: String, width: Float = 1f) =
        ResolvedKey(label, KeyAction.Text(label), width)

    @Test
    fun topRowKeysAreEvenlySpaced() {
        assertThat(labelAt(50f, 50f)).isEqualTo("q")
        assertThat(labelAt(950f, 50f)).isEqualTo("p")
    }

    @Test
    fun shortRowIsCentred() {
        val a = geometry.slots.first { it.key.label == "a" }
        assertThat(a.left).isWithin(0.01f).of(50f)
    }

    @Test
    fun touchInMarginOfCentredRowMapsToNearestKey() {
        // x=10 is left of "a" (which starts at 50) in the second row.
        assertThat(labelAt(10f, 150f)).isEqualTo("a")
    }

    @Test
    fun wideKeysCoverTheirWeight() {
        val space = geometry.slots.first { it.key.action == KeyAction.Space }
        // 10 units minus five side keys of 0.45.
        assertThat(space.right - space.left).isWithin(0.5f).of(775f)
    }

    @Test
    fun slotsTileWithoutGaps() {
        val firstRow = geometry.slots.filter { it.top == 0f }
        firstRow.zipWithNext().forEach { (a, b) -> assertThat(b.left).isEqualTo(a.right) }
        // Rows tile vertically and fill the box.
        val tops = geometry.slots.map { it.top }.distinct().sorted()
        val bottoms = geometry.slots.map { it.bottom }.distinct().sorted()
        assertThat(tops.drop(1)).containsExactlyElementsIn(bottoms.dropLast(1)).inOrder()
        assertThat(bottoms.last()).isEqualTo(400f)
    }

    @Test
    fun rowHeightsFollowWeights() {
        val layer =
            KeyboardLayer(
                "t",
                listOf(
                    ResolvedRow(listOf(key("a")), height = 1f),
                    ResolvedRow(listOf(key("b")), height = 3f),
                ),
            )
        val g = KeyboardGeometry(layer, 100f, 400f)
        assertThat(g.slots[0].bottom - g.slots[0].top).isWithin(0.01f).of(100f)
        assertThat(g.slots[1].top).isEqualTo(g.slots[0].bottom)
        assertThat(g.slots[1].bottom - g.slots[1].top).isWithin(0.01f).of(300f)
    }

    @Test
    fun spacerTakesSpaceButNeverHits() {
        val spacer = ResolvedKey("", KeyAction.None, 2f)
        val layer = KeyboardLayer("t", listOf(ResolvedRow(listOf(key("a"), spacer, key("b")))))
        val g = KeyboardGeometry(layer, 400f, 100f)
        assertThat(g.slots).hasSize(3)
        assertThat(g.slots[1].left).isEqualTo(100f)
        assertThat(g.slots[1].right).isEqualTo(300f)
        // Left half of the gap goes to "a", right half to "b".
        assertThat(g.hitTest(150f, 50f)?.key?.label).isEqualTo("a")
        assertThat(g.hitTest(250f, 50f)?.key?.label).isEqualTo("b")
        for (x in 0..399 step 10) assertThat(g.hitTest(x.toFloat(), 50f)!!.key.isSpacer).isFalse()
    }

    @Test
    fun gridUnitsSetsTheUnitForNarrowLayers() {
        val rows = listOf(ResolvedRow(listOf(key("a"), key("b"))))
        val plain = KeyboardGeometry(KeyboardLayer("t", rows), 1000f, 100f)
        assertThat(plain.slots[0].right - plain.slots[0].left).isEqualTo(500f)
        val grid = KeyboardGeometry(KeyboardLayer("t", rows, gridUnits = 10f), 1000f, 100f)
        assertThat(grid.slots[0].right - grid.slots[0].left).isEqualTo(100f)
        assertThat(grid.slots[0].left).isEqualTo(400f) // still centred
    }

    @Test
    fun rowsWiderThanTheGridShrinkTheUnit() {
        val row = ResolvedRow(List(12) { key("k$it") })
        val g = KeyboardGeometry(KeyboardLayer("t", listOf(row), gridUnits = 10f), 1200f, 100f)
        assertThat(g.slots[0].right - g.slots[0].left).isEqualTo(100f)
    }

    @Test
    fun emptyLayerHasNoHit() {
        val g = KeyboardGeometry(KeyboardLayer("t", emptyList()), 100f, 100f)
        assertThat(g.slots).isEmpty()
        assertThat(g.hitTest(10f, 10f)).isNull()
    }

    @Test
    fun slotIndicesFollowRowOrder() {
        assertThat(geometry.slots.map { it.index }).isEqualTo(geometry.slots.indices.toList())
        val q = geometry.slots[0]
        assertThat(q.contains(q.centerX, q.centerY)).isTrue()
        assertThat(q.contains(q.right, q.centerY)).isFalse()
    }
}

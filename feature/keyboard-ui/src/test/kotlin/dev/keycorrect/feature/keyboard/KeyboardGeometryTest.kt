package dev.keycorrect.feature.keyboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeyboardGeometryTest {
    private val geometry = KeyboardGeometry(BuiltInLayouts.letters, width = 1000f, height = 400f)

    private fun labelAt(x: Float, y: Float) = geometry.hitTest(x, y)?.key?.label

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
        assertThat(space.right - space.left).isWithin(0.01f).of(500f)
    }

    @Test
    fun slotsTileWithoutGaps() {
        val firstRow = geometry.slots.filter { it.top == 0f }
        firstRow.zipWithNext().forEach { (a, b) -> assertThat(b.left).isEqualTo(a.right) }
    }
}

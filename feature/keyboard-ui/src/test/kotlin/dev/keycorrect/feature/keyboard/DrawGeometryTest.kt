package dev.keycorrect.feature.keyboard

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.layout.KeyboardLayer
import dev.keycorrect.core.layout.ResolvedKey
import dev.keycorrect.core.layout.ResolvedRow
import org.junit.Test

class DrawGeometryTest {
    private val layer =
        KeyboardLayer("t", listOf(ResolvedRow(listOf(ResolvedKey("a", KeyAction.Text("a"), 1f)))))

    @Test
    fun staleComposedGeometryIsReplacedByOneAtTheDrawnSize() {
        val stale = KeyboardGeometry(layer, 1f, 1f) // composed at a tiny first size
        val drawn = DrawGeometry().at(stale, 1000f, 400f)
        assertThat(drawn.slots[0].right).isEqualTo(1000f)
        assertThat(drawn.slots[0].bottom).isEqualTo(400f)
    }

    @Test
    fun matchingComposedGeometryIsUsedAsIs() {
        val g = KeyboardGeometry(layer, 1000f, 400f)
        assertThat(DrawGeometry().at(g, 1000f, 400f)).isSameInstanceAs(g)
    }

    @Test
    fun rebuiltGeometryIsReusedUntilTheSizeChanges() {
        val stale = KeyboardGeometry(layer, 1f, 1f)
        val d = DrawGeometry()
        val first = d.at(stale, 1000f, 400f)
        assertThat(d.at(stale, 1000f, 400f)).isSameInstanceAs(first)
        assertThat(d.at(stale, 500f, 400f)).isNotSameInstanceAs(first)
    }

    @Test
    fun touchGeometryFollowsTheMeasuredSizeWithoutRecomposition() {
        val composed = KeyboardGeometry(layer, 1f, 1f)
        val d = DrawGeometry()
        assertThat(d.latest(composed)).isSameInstanceAs(composed) // not measured yet
        d.measured(1000, 400)
        val g = d.latest(composed)
        assertThat(g.hitTest(900f, 300f)?.key?.label).isEqualTo("a")
        assertThat(g.slots[0].right).isEqualTo(1000f)
    }
}

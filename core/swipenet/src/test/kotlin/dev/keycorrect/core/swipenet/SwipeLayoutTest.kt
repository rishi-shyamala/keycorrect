package dev.keycorrect.core.swipenet

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.ComposeOptions
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.layout.KeyboardSet
import org.junit.Test

class SwipeLayoutTest {
    private fun qwerty(numberRow: Boolean = false, width: Float = 1080f, height: Float = 900f) =
        KeyboardGeometry(
            KeyboardComposer(BuiltInLayouts.repository())
                .compose(
                    KeyboardSet.Default,
                    KeyboardSet.LETTERS,
                    options = ComposeOptions(numberRow = numberRow),
                ),
            width,
            height,
        )

    @Test
    fun ourQwertyLandsInFutosFrame() {
        val layout = SwipeLayout.of(qwerty())!!
        assertThat(layout.chars.joinToString("")).isEqualTo("qwertyuiopasdfghjklzxcvbnm")
        fun centre(c: Char) = layout.chars.indexOf(c).let { layout.keys[2 * it] to layout.keys[2 * it + 1] }
        // The model card's QWERTY: q (0.05, 0.167), a (0.10, 0.5), z (0.20, 0.833), m (0.80, 0.833).
        for ((c, x, y) in
            listOf(
                Triple('q', 0.05f, 1 / 6f),
                Triple('a', 0.10f, 0.5f),
                Triple('z', 0.20f, 5 / 6f),
                Triple('m', 0.80f, 5 / 6f),
                Triple('p', 0.95f, 1 / 6f),
            )) {
            val (cx, cy) = centre(c)
            assertThat(cx).isWithin(1e-4f).of(x)
            assertThat(cy).isWithin(1e-4f).of(y)
        }
    }

    @Test
    fun theGoldensOurQwertyCasesUseOurRealLayout() {
        val layout = SwipeLayout.of(qwerty())!!
        val ours = Goldens.cases.filter { "our QWERTY" in it.name }
        assertThat(ours).isNotEmpty()
        for (case in ours) {
            assertThat(case.chars).isEqualTo(layout.chars.joinToString(""))
            assertThat(layout.keys).usingTolerance(1e-6).containsExactly(case.keys).inOrder()
            assertThat(layout.mask).isEqualTo(case.mask)
        }
    }

    @Test
    fun theFrameIgnoresTheNumberRowAndTheKeyboardsSize() {
        val plain = SwipeLayout.of(qwerty())!!
        val tall = SwipeLayout.of(qwerty(numberRow = true, width = 1440f, height = 1500f))!!
        assertThat(tall.chars).isEqualTo(plain.chars)
        assertThat(tall.keys).usingTolerance(1e-5).containsExactly(plain.keys).inOrder()
    }

    @Test
    fun touchesAreMappedIntoTheFrame() {
        val geometry = qwerty()
        val layout = SwipeLayout.of(geometry)!!
        val q = geometry.slots.first { it.key.label.equals("q", ignoreCase = true) }
        assertThat(layout.frame.x(q.left)).isWithin(1e-6f).of(0f)
        assertThat(layout.frame.y(q.top)).isWithin(1e-6f).of(0f)
        // A touch on the space bar is below the letters: clamped to the bottom edge by the
        // resampler.
        val f = layout.features(floatArrayOf(q.centerX, q.centerX), floatArrayOf(q.centerY, 2000f), floatArrayOf(0f, 100f))
        assertThat(f[SwipeResampler.POINTS]).isWithin(1e-5f).of(1 / 6f)
        assertThat(f[2 * SwipeResampler.POINTS - 1]).isEqualTo(1f)
    }

    @Test
    fun paddingAndLimits() {
        val small = SwipeLayout(SwipeFrame(0f, 0f, 1f, 1f), listOf('a', 'b'), listOf(0.2f to 0.3f, 0.8f to 0.9f))
        assertThat(small.keys.take(4)).containsExactly(0.2f, 0.3f, 0.8f, 0.9f).inOrder()
        assertThat(small.keys.drop(4).all { it == 0f }).isTrue()
        assertThat(small.mask.count { it == 1.toByte() }).isEqualTo(2)
        assertThat(small.mask.take(2)).containsExactly(1.toByte(), 1.toByte())

        val tooMany = (0..SwipeLayout.MAX_KEYS).map { 'a' }
        val result = runCatching {
            SwipeLayout(SwipeFrame(0f, 0f, 1f, 1f), tooMany, tooMany.map { 0f to 0f })
        }
        assertThat(result.isFailure).isTrue()
    }
}

package dev.keycorrect.tools.eval

import com.google.common.truth.Truth.assertThat
import java.util.Random
import kotlin.math.hypot
import org.junit.Test

class SwipeGeneratorTest {
    private val keyboard = EvalKeyboard()
    private val exact = SwipeGenerator(keyboard, SwipeConfig(noise = 0f))
    private val noisy = SwipeGenerator(keyboard, SwipeConfig(noise = 0.25f))

    private fun distanceKeyWidths(x: Float, y: Float, c: Char): Double {
        val k = keyboard.key(c)!!
        return hypot((x - k.centerX).toDouble(), (y - k.centerY).toDouble()) / k.width
    }

    @Test
    fun noiseFreeControlPointsAreTheCollapsedKeyCentres() {
        val points = exact.controlPoints("hello", Random(1))
        assertThat(points).hasSize(4) // h e l o: the double l collapses
        "helo"
            .forEachIndexed { i, c ->
                assertThat(points[i][0]).isEqualTo(keyboard.key(c)!!.centerX.toDouble())
                assertThat(points[i][1]).isEqualTo(keyboard.key(c)!!.centerY.toDouble())
            }
    }

    @Test
    fun noiseFreePathPassesThroughEveryKey() {
        val g = exact.generate("keyboard", Random(3))
        for (c in "keyboard") {
            val nearest = g.points.minOf { distanceKeyWidths(it.x, it.y, c) }
            assertThat(nearest).isLessThan(0.2)
        }
        // The very first sample is exactly on the first key.
        assertThat(g.points.first().x).isEqualTo(keyboard.key('k')!!.centerX)
        assertThat(g.points.first().y).isEqualTo(keyboard.key('k')!!.centerY)
    }

    @Test
    fun startsAtFirstKeyAndEndsNearLastKey() {
        repeat(50) { seed ->
            val g = noisy.generate("people", Random(seed.toLong()))
            assertThat(distanceKeyWidths(g.points.first().x, g.points.first().y, 'p'))
                .isLessThan(1.2)
            assertThat(distanceKeyWidths(g.points.last().x, g.points.last().y, 'e')).isLessThan(1.5)
        }
        val g = exact.generate("people", Random(1))
        // Without noise only the overshoot (0.25 key widths) separates the end from the key.
        assertThat(distanceKeyWidths(g.points.last().x, g.points.last().y, 'e')).isLessThan(0.3)
    }

    @Test
    fun timestampsIncreaseAtAboutOneHundredHertz() {
        for (word in listOf("a", "to", "hello", "extraordinary")) {
            val t = noisy.generate(word, Random(5)).points.map { it.timeMillis }
            assertThat(t.first()).isEqualTo(0L)
            assertThat(t.zipWithNext().all { (a, b) -> b > a }).isTrue()
            // All steps are the 10 ms sampling interval except possibly the last.
            assertThat(t.zipWithNext { a, b -> b - a }.dropLast(1).all { it == 10L }).isTrue()
        }
    }

    @Test
    fun sameSeedSameGestureDifferentSeedDifferent() {
        assertThat(noisy.generate("swipe", Random(9))).isEqualTo(noisy.generate("swipe", Random(9)))
        assertThat(noisy.generate("swipe", Random(9)))
            .isNotEqualTo(noisy.generate("swipe", Random(10)))
    }

    @Test
    fun singleKeyWordsMakeAShortDragNearTheKey() {
        for (word in listOf("a", "I", "ss")) {
            val g = noisy.generate(word, Random(2))
            assertThat(g.points.size).isAtLeast(2)
            val c = word.lowercase()[0]
            assertThat(g.points.all { distanceKeyWidths(it.x, it.y, c) < 1.6 }).isTrue()
        }
    }

    @Test
    fun speedIsHighestInTheMiddleOfASegment() {
        // Two far keys: the steps near the ends are shorter than the steps in the middle.
        val g = exact.generate("qp", Random(1)).points.dropLast(1) // drop the overshoot tail
        val steps = g.zipWithNext { a, b -> hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) }
        assertThat(steps[steps.size / 2]).isGreaterThan(steps.first() * 1.3)
    }

    @Test
    fun jitterMatchesTheRequestedNoise() {
        val rng = Random(11)
        val key = keyboard.key('g')!!
        val dx = List(4000) { (noisy.controlPoints("g", rng)[0][0] - key.centerX) / key.width }
        val mean = dx.average()
        val sd = Math.sqrt(dx.sumOf { (it - mean) * (it - mean) } / dx.size)
        assertThat(mean).isWithin(0.02).of(0.0)
        assertThat(sd).isWithin(0.02).of(0.25)
    }

    @Test
    fun canSwipeWhatHasKeys() {
        assertThat(exact.canSwipe("don't")).isTrue()
        assertThat(exact.canSwipe("it’s")).isTrue()
        assertThat(exact.canSwipe("b2b")).isFalse()
        assertThat(exact.canSwipe("naïve")).isFalse()
        assertThat(exact.canSwipe("'")).isFalse()
        assertThat(exact.canSwipe("")).isFalse()
    }
}

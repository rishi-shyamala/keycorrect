package dev.keycorrect.tools.eval

import com.google.common.truth.Truth.assertThat
import java.util.Random
import org.junit.Test

class TypistTest {
    private val keyboard = EvalKeyboard()

    private fun typist(config: TypistConfig) = Typist(keyboard, config)

    private val noTypos =
        TypistConfig(omissionRate = 0f, insertionRate = 0f, transpositionRate = 0f)

    @Test
    fun keyboardHasAllLettersAtPhoneSize() {
        assertThat(('a'..'z').all { keyboard.hasKey(it) }).isTrue()
        assertThat(keyboard.widthPx).isEqualTo(1080f)
        assertThat(keyboard.heightPx).isWithin(0.01f).of(4 * 54f * 2.75f)
    }

    @Test
    fun sameSeedGivesIdenticalTouches() {
        val t = typist(TypistConfig())
        repeat(50) { seed ->
            val a = t.type("keyboard", Random(seed.toLong()))
            val b = t.type("keyboard", Random(seed.toLong()))
            assertThat(a.input).isEqualTo(b.input)
            assertThat(a.typo).isEqualTo(b.typo)
        }
    }

    @Test
    fun differentSeedsDiffer() {
        val t = typist(noTypos)
        val touches =
            (0 until 20).map { t.type("keyboard", Random(it.toLong())).input.touches }.toSet()
        assertThat(touches.size).isGreaterThan(15)
    }

    @Test
    fun zeroSigmaHitsKeyCentres() {
        val t = typist(noTypos.copy(sigmaX = 0f, sigmaY = 0f))
        val typed = t.type("hello", Random(1))
        assertThat(typed.text).isEqualTo("hello")
        assertThat(typed.touchSlips).isEqualTo(0)
        typed.input.touches.forEachIndexed { i, p ->
            val key = keyboard.key("hello"[i])!!
            assertThat(p!!.x).isWithin(1e-3f).of(key.centerX)
            assertThat(p.y).isWithin(1e-3f).of(key.centerY)
        }
    }

    @Test
    fun touchSpreadMatchesConfiguredSigma() {
        val sigma = 0.2f
        val t = typist(noTypos.copy(sigmaX = sigma, sigmaY = sigma))
        val key = keyboard.key('g')!!
        val dx = ArrayList<Double>()
        val dy = ArrayList<Double>()
        val rng = Random(7)
        repeat(4000) {
            val p = t.type("g", rng).input.touches[0]!!
            dx.add((p.x - key.centerX) / key.width.toDouble())
            dy.add((p.y - key.centerY) / key.height.toDouble())
        }
        assertThat(dx.average()).isWithin(0.02).of(0.0)
        assertThat(dy.average()).isWithin(0.02).of(0.0)
        assertThat(std(dx)).isWithin(0.02).of(sigma.toDouble())
        assertThat(std(dy)).isWithin(0.02).of(sigma.toDouble())
    }

    @Test
    fun tapsLandingOnNeighbourInsertTheNeighbourLetter() {
        // With a huge sigma the typed letters stop matching the target.
        val t = typist(noTypos.copy(sigmaX = 1.5f, sigmaY = 1.5f))
        val typed = t.type("keyboard", Random(3))
        assertThat(typed.text).isNotEqualTo("keyboard")
        assertThat(typed.touchSlips).isGreaterThan(0)
    }

    @Test
    fun typoRatesAreRespected() {
        val config =
            TypistConfig(
                sigmaX = 0f,
                sigmaY = 0f,
                omissionRate = 0.1f,
                insertionRate = 0.1f,
                transpositionRate = 0.1f,
            )
        val t = typist(config)
        val rng = Random(11)
        val counts = HashMap<TypoKind, Int>()
        val n = 6000
        repeat(n) {
            val typed = t.type("keyboard", rng)
            counts.merge(typed.typo, 1, Int::plus)
            when (typed.typo) {
                TypoKind.None -> assertThat(typed.text).isEqualTo("keyboard")
                TypoKind.Omission -> assertThat(typed.text.length).isEqualTo(7)
                TypoKind.Insertion -> assertThat(typed.text.length).isEqualTo(9)
                TypoKind.Transposition -> {
                    assertThat(typed.text).isNotEqualTo("keyboard")
                    assertThat(typed.text.toList().sorted()).isEqualTo("keyboard".toList().sorted())
                }
            }
        }
        for (kind in listOf(TypoKind.Omission, TypoKind.Insertion, TypoKind.Transposition)) {
            assertThat(counts.getValue(kind) / n.toDouble()).isWithin(0.02).of(0.1)
        }
    }

    @Test
    fun apostropheAndHyphenAreNullTouchChars() {
        val t = typist(noTypos)
        val typed = t.type("don't-go", Random(1))
        assertThat(typed.text.length).isEqualTo(8)
        assertThat(typed.input.touches[3]).isNull()
        assertThat(typed.input.touches[5]).isNull()
        assertThat(typed.input.touches[0]).isNotNull()
    }

    @Test
    fun keepsCaseOfTheTarget() {
        val t = typist(noTypos.copy(sigmaX = 0f, sigmaY = 0f))
        assertThat(t.type("Hello", Random(1)).text).isEqualTo("Hello")
    }

    @Test
    fun digitsAreNotTypeable() {
        val t = typist(TypistConfig())
        assertThat(t.canType("4th")).isFalse()
        assertThat(t.canType("fourth")).isTrue()
    }

    private fun std(v: List<Double>): Double {
        val m = v.average()
        return Math.sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }
}

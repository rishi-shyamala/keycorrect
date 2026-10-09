package dev.keycorrect.tools.eval

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.decoder.ClassicConfig
import org.junit.Assert.assertThrows
import org.junit.Test

class ConfigAndSweepTest {
    @Test
    fun overridesChangeTheNamedFields() {
        val c =
            ConfigOverrides.apply(
                ClassicConfig(),
                ConfigOverrides.parse(
                    "autocorrect.threshold=3.5, edits.maxEdits=4,spatial.sigma=0.6,autocorrect.correctAllCaps=true"
                ),
            )
        assertThat(c.autocorrect.threshold).isEqualTo(3.5f)
        assertThat(c.edits.maxEdits).isEqualTo(4)
        assertThat(c.spatial.sigmaXFactor).isEqualTo(0.6f)
        assertThat(c.spatial.sigmaYFactor).isEqualTo(0.6f)
        assertThat(c.autocorrect.correctAllCaps).isTrue()
        // Everything else is untouched.
        assertThat(
                c.copy(
                    autocorrect = ClassicConfig().autocorrect,
                    edits = ClassicConfig().edits,
                    spatial = ClassicConfig().spatial,
                )
            )
            .isEqualTo(ClassicConfig())
    }

    @Test
    fun multiplierSyntaxScalesTheDefault() {
        val base = ClassicConfig()
        val c = ConfigOverrides.apply(base, mapOf("autocorrect.threshold" to "x2"))
        assertThat(c.autocorrect.threshold).isWithin(1e-6f).of(base.autocorrect.threshold * 2)
        assertThat(ConfigOverrides.apply(base, mapOf("autocorrect.threshold" to "x1")))
            .isEqualTo(base)
    }

    @Test
    fun unknownKeysAndBadValuesFail() {
        assertThrows(IllegalArgumentException::class.java) {
            ConfigOverrides.apply(ClassicConfig(), mapOf("autocorrect.thresold" to "1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConfigOverrides.apply(ClassicConfig(), mapOf("nope.x" to "1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConfigOverrides.apply(ClassicConfig(), mapOf("edits.maxEdits" to "1.5"))
        }
    }

    @Test
    fun everyAdvertisedKeyIsApplicable() {
        for (key in ConfigOverrides.KEYS) {
            // Numbers accept the multiplier syntax; booleans take true/false.
            val applied = runCatching {
                ConfigOverrides.apply(ClassicConfig(), mapOf(key to "x1"))
            }
                .recoverCatching { ConfigOverrides.apply(ClassicConfig(), mapOf(key to "false")) }
            assertThat(applied.isSuccess).isTrue()
        }
    }

    @Test
    fun gridIsTheCartesianProduct() {
        val g = Sweep.grid("a.x=1,2;b.y=3,4,5")
        assertThat(g).hasSize(6)
        assertThat(g.first()).containsExactly("a.x", "1", "b.y", "3")
        assertThat(Sweep.grid(Options.DEFAULT_GRID)).isNotEmpty()
    }

    @Test
    fun paretoAndRecommendation() {
        // 1000 words: 500 typed right (falseFixes of them broken), 500 typed wrong (fixed of them
        // repaired).
        fun point(name: String, fixed: Int, falseFixes: Int): SweepPoint {
            val m = Metrics()
            fun word(literal: String, committed: String) =
                m.record(
                    WordOutcome(
                        "ok",
                        literal,
                        committed,
                        emptyList(),
                        false,
                        TypoKind.None,
                        0,
                        true,
                        2,
                        null,
                        null,
                    )
                )
            repeat(falseFixes) { word("ok", "x") }
            repeat(500 - falseFixes) { word("ok", "ok") }
            repeat(fixed) { word("bad", "ok") }
            repeat(500 - fixed) { word("bad", "bad") }
            return SweepPoint(mapOf("p" to name), m)
        }
        val a = point("a", 300, 1)
        val b = point("b", 400, 5)
        val c = point("c", 300, 8) // dominated by b
        val points = listOf(a, b, c)
        val frontier = Sweep.pareto(points)
        assertThat(frontier).contains(a)
        assertThat(frontier).contains(b)
        assertThat(frontier).doesNotContain(c)
        assertThat(Sweep.recommend(points, 3.0)).isSameInstanceAs(a)
        assertThat(Sweep.recommend(points, 10.0)).isSameInstanceAs(b)
        assertThat(Sweep.recommend(points, 0.1)).isNull()
    }

    @Test
    fun jsonEscapesAndNests() {
        val s = Json.write(mapOf("a" to listOf(1, 2), "b" to "q\"\n", "c" to mapOf("d" to 0.5)))
        assertThat(s).contains("\"a\": [1, 2]")
        assertThat(s).contains("\"q\\\"\\n\"")
        assertThat(s).contains("0.5")
    }

    @Test
    fun enumFlagsConsumeExactlyOneValue() {
        val o = Options.parse(listOf("--names", "keep", "--split", "dev", "--seed", "7"))
        assertThat(o.names).isEqualTo(NameMode.Keep)
        assertThat(o.split).isEqualTo(Split.Dev)
        assertThat(o.seed).isEqualTo(7L)
    }
}

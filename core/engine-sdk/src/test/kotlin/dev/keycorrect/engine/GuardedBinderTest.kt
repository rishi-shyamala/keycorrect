package dev.keycorrect.engine

import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class GuardedBinderTest {
    private val runner = BoundedRunner("test-engine", 2)

    @After
    fun tearDown() = runner.shutdown()

    /** Handlers that record what they were asked and answer with [onScore] / [onGenerate]. */
    private class Handlers(
        var onScore: (Array<String>, Long) -> FloatArray? = { c, _ -> FloatArray(c.size) },
        var onGenerate: (Int, Long) -> String? = { _, _ -> "ok" },
    ) : EngineHandlers {
        var lastTimeout = 0L
        var lastTokens = 0

        override fun info() = Bundle()

        override fun configure(options: Bundle) = Unit

        override fun score(context: String, candidates: Array<String>, timeoutMs: Long): FloatArray? {
            lastTimeout = timeoutMs
            return onScore(candidates, timeoutMs)
        }

        override fun generate(prompt: String, maxTokens: Int, timeoutMs: Long): String? {
            lastTimeout = timeoutMs
            lastTokens = maxTokens
            return onGenerate(maxTokens, timeoutMs)
        }
    }

    private fun binder(h: Handlers) = GuardedBinder(h, runner)

    private fun a(vararg texts: String?): Array<String?> = arrayOf(*texts)

    @Test
    fun reportsTheApiVersion() {
        assertThat(binder(Handlers()).apiVersion()).isEqualTo(EngineContract.API_VERSION)
    }

    @Test
    fun scoresPassThroughWithTheTimeoutLimited() {
        val h = Handlers(onScore = { c, _ -> FloatArray(c.size) { -1f - it } })
        val r = binder(h).score("hello", a("a", "b"), 60_000L)

        assertThat(r).usingExactEquality().containsExactly(-1f, -2f).inOrder()
        assertThat(h.lastTimeout).isEqualTo(EngineContract.MAX_TIMEOUT_MS)
        binder(h).score("hello", a("a"), -5L)
        assertThat(h.lastTimeout).isEqualTo(1L)
    }

    @Test
    fun anExceptionBecomesNull() {
        val h = Handlers(onScore = { _, _ -> error("boom") }, onGenerate = { _, _ -> error("x") })

        assertThat(binder(h).score("c", a("a"), 100L)).isNull()
        assertThat(binder(h).generate("p", 4, 100L)).isNull()
    }

    @Test
    fun anErrorBecomesNull() {
        val h = Handlers(onScore = { _, _ -> throw UnsatisfiedLinkError("native") })

        assertThat(binder(h).score("c", a("a"), 100L)).isNull()
    }

    @Test
    fun aCallThatHangsIsAbandoned() {
        val h = Handlers(onScore = { _, _ -> Thread.sleep(10_000).let { null } })
        val started = System.nanoTime()

        assertThat(binder(h).score("c", a("a"), 50L)).isNull()
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1500L)
    }

    @Test
    fun refusesBadInput() {
        val h = Handlers()
        val b = binder(h)

        assertThat(b.score(null, a("a"), 100L)).isNull()
        assertThat(b.score("c", null, 100L)).isNull()
        assertThat(b.score("c", a(), 100L)).isNull()
        assertThat(b.score("c", a("a", null), 100L)).isNull()
        val tooMany = a(*Array(EngineContract.MAX_CANDIDATES + 1) { "w$it" })
        assertThat(b.score("c", tooMany, 100L)).isNull()
        val long = "x".repeat(EngineContract.MAX_TEXT_CHARS + 1)
        assertThat(b.score(long, a("a"), 100L)).isNull()
        assertThat(b.score("c", a(long), 100L)).isNull()
        assertThat(b.generate(null, 4, 100L)).isNull()
        assertThat(b.generate(long, 4, 100L)).isNull()
        assertThat(b.generate("p", 0, 100L)).isNull()
    }

    @Test
    fun anAnswerOfTheWrongSizeOrWithNanIsRefused() {
        val h = Handlers(onScore = { _, _ -> floatArrayOf(1f) })
        assertThat(binder(h).score("c", a("a", "b"), 100L)).isNull()

        h.onScore = { c, _ -> FloatArray(c.size) { Float.NaN } }
        assertThat(binder(h).score("c", a("a"), 100L)).isNull()
    }

    @Test
    fun generateLimitsTheTokens() {
        val h = Handlers()

        assertThat(binder(h).generate("p", 5000, 100L)).isEqualTo("ok")
        assertThat(h.lastTokens).isEqualTo(EngineContract.MAX_GENERATE_TOKENS)
    }

    @Test
    fun whenAllWorkersAreStuckNewCallsAreRefusedAtOnce() {
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        // A handler that ignores its interruption, like native code that can't be stopped.
        val h =
            Handlers(
                onScore = { _, _ ->
                    while (!stop.get()) {
                        try {
                            Thread.sleep(5)
                        } catch (_: InterruptedException) {
                            // Keep going.
                        }
                    }
                    null
                }
            )
        val b = binder(h)
        repeat(2) { assertThat(b.score("c", a("a"), 20L)).isNull() }
        val started = System.nanoTime()

        assertThat(b.generate("p", 1, 1000L)).isNull()
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(100L)
        stop.set(true)
    }
}

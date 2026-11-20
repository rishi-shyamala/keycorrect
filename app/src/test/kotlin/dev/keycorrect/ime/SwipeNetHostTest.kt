package dev.keycorrect.ime

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.packs.PackProblem
import dev.keycorrect.core.packs.SwipePackResult
import dev.keycorrect.core.swipenet.SwipeEncoder
import dev.keycorrect.core.swipenet.SwipeNetBackend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test

/** When the keyboard's swipes get the encoder, and when they fall back to SHARK² alone. */
@OptIn(ExperimentalCoroutinesApi::class)
class SwipeNetHostTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher)
    private var now = 0L

    private val encoder =
        SwipeEncoder.open(
            ByteArray(1),
            backend =
                object : SwipeNetBackend {
                    override fun open(model: ByteArray, threads: Int) = 1L

                    override fun run(
                        handle: Long,
                        features: FloatArray,
                        keys: FloatArray,
                        mask: ByteArray,
                        out: FloatArray,
                    ) = true

                    override fun close(handle: Long) = Unit
                },
        )

    private fun host(results: ArrayDeque<() -> SwipePackResult>): Pair<SwipeNetHost, () -> Int> {
        var opens = 0
        val host =
            SwipeNetHost(scope, dispatcher, clock = { now }) {
                opens++
                results.removeFirst()()
            }
        return host to { opens }
    }

    @Test
    fun theFirstSwipeStartsTheLoadAndLaterSwipesGetTheEncoder() {
        val (host, opens) = host(ArrayDeque(listOf({ SwipePackResult.Available(encoder, "1") })))
        // The load runs off the asking thread; this first swipe decodes with SHARK² alone.
        assertThat(host.encoder()).isNull()
        assertThat(host.encoder()).isSameInstanceAs(encoder)
        assertThat(host.encoder()).isSameInstanceAs(encoder)
        assertThat(opens()).isEqualTo(1)
    }

    @Test
    fun aMissingPackIsLookedForAgainAfterTheRetryDelay() {
        val (host, opens) =
            host(
                ArrayDeque(
                    listOf(
                        { SwipePackResult.Unavailable(PackProblem.NotInstalled) },
                        { SwipePackResult.Available(encoder, "1") },
                    )
                )
            )
        assertThat(host.encoder()).isNull()
        assertThat(host.encoder()).isNull()
        assertThat(opens()).isEqualTo(1)
        now += SwipeNetHost.RETRY_NANOS
        assertThat(host.encoder()).isNull()
        assertThat(host.encoder()).isSameInstanceAs(encoder)
        assertThat(opens()).isEqualTo(2)
    }

    @Test
    fun anUntrustedOrBrokenPackIsNeverRetried() {
        for (failure in
            listOf<() -> SwipePackResult>(
                { SwipePackResult.Unavailable(PackProblem.SignatureMismatch) },
                { SwipePackResult.Unavailable(null, "SwipeNetException") },
                { error("loader crashed") },
            )) {
            val (host, opens) = host(ArrayDeque(listOf(failure)))
            assertThat(host.encoder()).isNull()
            now += 10 * SwipeNetHost.RETRY_NANOS
            assertThat(host.encoder()).isNull()
            assertThat(opens()).isEqualTo(1)
        }
    }

    @Test
    fun aLoadThatFinishesAfterTheAskerIsGoneStillLandsInTheHost() {
        // The service that asked has been destroyed by the time the load finishes; the host (one
        // per
        // process, on the application scope) keeps the encoder for the next service.
        val gate = java.util.concurrent.CountDownLatch(1)
        val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        var opens = 0
        val host =
            SwipeNetHost(appScope, kotlinx.coroutines.Dispatchers.IO) {
                opens++
                gate.await()
                SwipePackResult.Available(encoder, "1")
            }
        assertThat(host.encoder()).isNull()
        // A second service instance asks while the first load is in flight: no second load.
        assertThat(host.encoder()).isNull()
        gate.countDown()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (host.encoder() == null && System.nanoTime() < deadline) Thread.sleep(10)
        assertThat(host.encoder()).isSameInstanceAs(encoder)
        assertThat(opens).isEqualTo(1)
        appScope.cancel()
    }
}

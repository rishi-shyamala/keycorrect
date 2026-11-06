package dev.keycorrect.neural

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.engine.EngineContract
import java.io.File
import java.util.concurrent.Executor
import org.junit.Test

class EngineClientTest {
    private class FakeLink : NeuralLink {
        var onConnected: () -> Unit = {}
        val configured = mutableListOf<Pair<ModelChoice, Int>>()
        var status: String? = null
        var scores: FloatArray? = null
        var statusCalls = 0
        var closed = false
        override var generation = 0L
        val admitted = mutableListOf<Long>()

        override fun admit(generation: Long) {
            admitted += generation
        }

        override fun configure(choice: ModelChoice, threads: Int): Boolean {
            configured += choice to threads
            return true
        }

        override fun status(): String? {
            statusCalls++
            return status
        }

        override fun score(context: String, candidates: List<String>, timeoutMs: Long) = scores

        override fun generate(prompt: String, maxTokens: Int, timeoutMs: Long): String? = null

        override fun close() {
            closed = true
        }
    }

    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private val link = FakeLink()
    private val queue = Queue()
    private var now = 100_000L
    private var model = ""
    private val files =
        mutableMapOf("0123456789abcdef" to File("/data/files/models/0123456789abcdef.gguf"))

    private fun client(
        threads: Int = 3,
        configures: Boolean = true,
        verify: (() -> Boolean)? = null,
        onRejected: () -> Unit = {},
    ) =
        EngineClient(
            { model },
            files::get,
            threads,
            { now },
            queue,
            configures,
            verify,
            onRejected,
        ) { onConnected ->
            link.onConnected = onConnected
            link
        }

    @Test
    fun configuresTheEngineEachTimeItConnects() {
        client()
        link.onConnected()
        queue.runAll()
        link.onConnected()
        queue.runAll()

        assertThat(link.configured)
            .containsExactly(ModelChoice.PackDefault to 3, ModelChoice.PackDefault to 3)
    }

    @Test
    fun aPluginIsNeverSentTheBuiltinOptions() {
        val c = client(configures = false)
        link.onConnected()
        c.reconfigure()
        link.status = EngineContract.STATUS_NO_MODEL
        now += EngineClient.HEAL_INTERVAL_MS
        c.score("a", listOf("b"), 10)
        queue.runAll()

        assertThat(link.configured).isEmpty()
    }

    @Test
    fun anImportedModelIsSentByItsPath() {
        model = "0123456789abcdef"
        val c = client()

        assertThat(c.choice())
            .isEqualTo(ModelChoice.Imported("/data/files/models/0123456789abcdef.gguf"))
        link.onConnected()
        queue.runAll()
        assertThat(link.configured.single().first)
            .isEqualTo(ModelChoice.Imported("/data/files/models/0123456789abcdef.gguf"))
    }

    @Test
    fun aModelThatIsGoneFallsBackToThePackDefault() {
        model = "ffffffffffffffff"

        assertThat(client().choice()).isEqualTo(ModelChoice.PackDefault)
    }

    @Test
    fun reconfigureSendsTheCurrentChoice() {
        val c = client()
        model = "0123456789abcdef"
        c.reconfigure()
        queue.runAll()

        assertThat(link.configured.single().first).isInstanceOf(ModelChoice.Imported::class.java)
    }

    @Test
    fun scoresPassThrough() {
        link.scores = floatArrayOf(-1f, -2f)

        assertThat(client().score("c", listOf("a", "b"), 40))
            .usingExactEquality()
            .containsExactly(-1f, -2f)
            .inOrder()
        assertThat(queue.tasks).isEmpty()
    }

    @Test
    fun anEngineThatLostItsModelIsConfiguredAgainButNotMoreOftenThanEveryThirtySeconds() {
        val c = client()
        link.status = "no_model"

        assertThat(c.score("c", listOf("a"), 40)).isNull()
        queue.runAll()
        assertThat(link.configured).hasSize(1)

        now += 29_000
        c.score("c", listOf("a"), 40)
        queue.runAll()
        assertThat(link.configured).hasSize(1)

        now += 1_000
        c.generate("p", 3, 40)
        queue.runAll()
        assertThat(link.configured).hasSize(2)
    }

    @Test
    fun anEngineThatIsLoadingOrReadyIsLeftAlone() {
        val c = client()
        for (status in listOf("loading", "ready", null)) {
            link.status = status
            now += 60_000
            c.score("c", listOf("a"), 40)
            queue.runAll()
        }

        assertThat(link.configured).isEmpty()
    }

    @Test
    fun anErrorStatusIsRetried() {
        val c = client()
        link.status = "error:NotInstalled"
        c.score("c", listOf("a"), 40)
        queue.runAll()

        assertThat(link.configured).hasSize(1)
    }

    @Test
    fun aPluginIsAdmittedOnlyAfterItPassesTheCheckOnEveryConnect() {
        var approved = true
        var rejected = 0
        client(configures = false, verify = { approved }, onRejected = { rejected++ })

        link.generation = 1
        link.onConnected()
        queue.runAll()
        assertThat(link.admitted).containsExactly(1L)
        assertThat(rejected).isEqualTo(0)
        assertThat(link.closed).isFalse()

        // It reconnects after its certificate changed: not admitted, link closed, host told.
        approved = false
        link.generation = 2
        link.onConnected()
        queue.runAll()
        assertThat(link.admitted).containsExactly(1L)
        assertThat(link.closed).isTrue()
        assertThat(rejected).isEqualTo(1)
        assertThat(link.configured).isEmpty()
    }

    @Test
    fun anErrorThatNoRetryCanFixStopsConfiguringUntilReconfigure() {
        val c = client()
        for (status in listOf("error:UnsupportedCpu", "error:CrashedOnLoad")) {
            link.status = status
            now += EngineClient.HEAL_INTERVAL_MS
            c.score("c", listOf("a"), 40)
            queue.runAll()
            // Neither the heal nor a reconnect configures again.
            link.onConnected()
            queue.runAll()
            c.reviveIfReleased()
            queue.runAll()
        }
        // The first connect (before any status was seen) configured once.
        assertThat(link.configured).isEmpty()

        c.reconfigure()
        queue.runAll()
        assertThat(link.configured).hasSize(1)
    }

    @Test
    fun reviveConfiguresOnlyAnEngineThatReleasedItsModel() {
        val c = client()
        for (status in listOf("loading", "ready", "error:LoadFailed", null)) {
            link.status = status
            c.reviveIfReleased()
            queue.runAll()
        }
        assertThat(link.configured).isEmpty()

        link.status = "no_model"
        c.reviveIfReleased()
        queue.runAll()
        assertThat(link.configured).hasSize(1)
    }

    @Test
    fun closeShutsDownTheExecutorAndLaterWorkIsDropped() {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val c =
            EngineClient({ model }, files::get, 0, { now }, pool, true, null, {}) { onConnected ->
                link.onConnected = onConnected
                link
            }
        c.close()

        assertThat(pool.isShutdown).isTrue()
        c.reconfigure()
        link.onConnected()
        c.score("c", listOf("a"), 40)
    }

    @Test
    fun closeClosesTheLink() {
        val c = client()
        c.close()

        assertThat(link.closed).isTrue()
    }
}

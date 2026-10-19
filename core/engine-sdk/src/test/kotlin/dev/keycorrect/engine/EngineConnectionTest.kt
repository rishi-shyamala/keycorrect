package dev.keycorrect.engine

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class EngineConnectionTest {
    private class FakeBinding(var accept: Boolean = true) : ServiceBinding {
        var connection: ServiceConnection? = null
        var binds = 0
        var unbinds = 0

        override fun bind(connection: ServiceConnection): Boolean {
            binds++
            this.connection = connection
            return accept
        }

        override fun unbind(connection: ServiceConnection) {
            unbinds++
        }
    }

    private class FakeEngine : ITextEngine.Stub() {
        var scores: FloatArray? = null
        var dead = false

        override fun apiVersion() = 1

        override fun info() = Bundle()

        override fun configure(options: Bundle?) = Unit

        override fun score(
            context: String?,
            candidates: Array<String>?,
            timeoutMs: Long,
        ): FloatArray? {
            if (dead) throw DeadObjectException()
            return scores ?: FloatArray(candidates!!.size) { -1f - it }
        }

        override fun generate(prompt: String?, maxTokens: Int, timeoutMs: Long) = "next"
    }

    private val binding = FakeBinding()
    private val engine = FakeEngine()
    private var now = 1_000L
    private var connectedCount = 0
    private val name = ComponentName("pkg", "pkg.Service")

    private val conn = EngineConnection(binding, { now }, { engine }, { connectedCount++ })

    @After fun tearDown() = conn.close()

    private fun connect() = binding.connection!!.onServiceConnected(name, Binder())

    @Test
    fun givesNullAtOnceWhileNotConnected() {
        assertThat(binding.binds).isEqualTo(1)
        assertThat(conn.isConnected).isFalse()
        assertThat(conn.score("c", listOf("a"), 100L)).isNull()
        assertThat(conn.generate("p", 3, 100L)).isNull()
        assertThat(conn.info()).isNull()
        assertThat(conn.configure(Bundle())).isFalse()
    }

    @Test
    fun answersOnceConnectedAndTellsTheListener() {
        connect()

        assertThat(connectedCount).isEqualTo(1)
        assertThat(conn.isConnected).isTrue()
        assertThat(conn.score("c", listOf("a", "b"), 100L))
            .usingExactEquality()
            .containsExactly(-1f, -2f)
            .inOrder()
        assertThat(conn.generate("p", 3, 100L)).isEqualTo("next")
        assertThat(conn.info()).isNotNull()
        assertThat(conn.configure(Bundle())).isTrue()
    }

    @Test
    fun anAnswerOfTheWrongSizeIsRefused() {
        connect()
        engine.scores = floatArrayOf(1f, 2f, 3f)

        assertThat(conn.score("c", listOf("a"), 100L)).isNull()
    }

    @Test
    fun aDeadEngineIsDroppedAndBoundAgainNoSoonerThanFiveSeconds() {
        connect()
        engine.dead = true
        assertThat(conn.score("c", listOf("a"), 100L)).isNull()
        assertThat(conn.isConnected).isFalse()

        now += 4_000
        assertThat(conn.score("c", listOf("a"), 100L)).isNull()
        assertThat(binding.binds).isEqualTo(1)

        now += 1_001
        conn.score("c", listOf("a"), 100L)
        assertThat(binding.binds).isEqualTo(2)
        assertThat(binding.unbinds).isEqualTo(1)

        // Connected again: it works, and the next loss waits only the base time again.
        engine.dead = false
        connect()
        assertThat(conn.score("c", listOf("a"), 100L)).isNotNull()
    }

    @Test
    fun theWaitDoublesWhileBindingKeepsFailing() {
        binding.connection!!.onServiceDisconnected(name)
        val waits = mutableListOf<Long>()
        repeat(4) {
            val before = binding.binds
            var waited = 0L
            while (binding.binds == before) {
                now += 1_000
                waited += 1_000
                conn.info()
            }
            waits += waited
        }

        assertThat(waits).containsExactly(5_000L, 10_000L, 20_000L, 40_000L).inOrder()
        assertThat(EngineConnection.retryDelayMs(10)).isEqualTo(EngineConnection.MAX_RETRY_MS)
    }

    @Test
    fun aServiceThatCannotBeBoundIsUnbound() {
        val refusing = FakeBinding(accept = false)
        val c = EngineConnection(refusing, { now }, { engine }, {})

        assertThat(refusing.unbinds).isEqualTo(1)
        assertThat(c.score("c", listOf("a"), 100L)).isNull()
        c.close()
    }

    @Test
    fun aDeadBindingBindsAgainAfterTheWait() {
        connect()
        binding.connection!!.onBindingDied(name)
        assertThat(conn.isConnected).isFalse()

        now += 5_000
        conn.info()
        assertThat(binding.binds).isEqualTo(2)
    }

    @Test
    fun closeUnbindsAndStopsEverything() {
        connect()
        conn.close()

        assertThat(binding.unbinds).isEqualTo(1)
        assertThat(conn.score("c", listOf("a"), 100L)).isNull()
        now += 600_000
        conn.info()
        assertThat(binding.binds).isEqualTo(1)
        conn.close()
        assertThat(binding.unbinds).isEqualTo(1)
    }

    @Test
    fun aLateDeathNoticeOfAnOldBinderLeavesTheNewEngineConnected() {
        val oldBinder = Binder()
        val newBinder = Binder()
        val oldEngine = FakeEngine()
        val newEngine = FakeEngine()
        val own = FakeBinding()
        val c =
            EngineConnection(
                own,
                { now },
                { if (it === oldBinder) oldEngine else newEngine },
                { connectedCount++ },
            )
        own.connection!!.onServiceConnected(name, oldBinder)
        own.connection!!.onServiceConnected(name, newBinder)

        c.binderDied(oldBinder)

        assertThat(c.isConnected).isTrue()
        assertThat(c.score("c", listOf("a"), 100L)).isNotNull()
        // The current binder's own notice does drop it.
        c.binderDied(newBinder)
        assertThat(c.isConnected).isFalse()
        c.close()
    }

    @Test
    fun anEngineThatNeedsAdmissionGetsNoCallsUntilAdmittedAndAgainAfterEveryReconnect() {
        val own = FakeBinding()
        val c = EngineConnection(own, { now }, { engine }, {}, requireAdmission = true)

        own.connection!!.onServiceConnected(name, Binder())
        assertThat(c.score("c", listOf("a"), 100L)).isNull()
        assertThat(c.info()).isNull()

        c.admit(c.generation - 1) // a stale admission does nothing
        assertThat(c.score("c", listOf("a"), 100L)).isNull()
        c.admit(c.generation)
        assertThat(c.score("c", listOf("a"), 100L)).isNotNull()

        // The component binds again by itself: the new engine has to be admitted again.
        own.connection!!.onServiceConnected(name, Binder())
        assertThat(c.score("c", listOf("a"), 100L)).isNull()
        c.admit(c.generation)
        assertThat(c.score("c", listOf("a"), 100L)).isNotNull()
        c.close()
    }

    @Test
    fun aConnectionAfterCloseIsIgnored() {
        conn.close()
        connect()

        assertThat(conn.isConnected).isFalse()
        assertThat(connectedCount).isEqualTo(0)
    }
}

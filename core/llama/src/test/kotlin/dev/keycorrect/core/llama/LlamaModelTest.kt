package dev.keycorrect.core.llama

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** The wrappers' rules, against a fake backend: no native library needed. */
class LlamaModelTest {
    private class FakeBackend(var modelHandle: Long = 100L, var contextHandle: Long = 200L) :
        LlamaBackend {
        val calls = mutableListOf<String>()
        var lastMmap: Boolean? = null

        override fun loadModelFile(path: String, useMmap: Boolean): Long {
            lastMmap = useMmap
            return modelHandle
        }

        override fun loadModelFd(fd: Int, offset: Long, useMmap: Boolean): Long {
            lastMmap = useMmap
            return modelHandle
        }

        var lastNCtx = 0

        override fun newContext(model: Long, nCtx: Int, nThreads: Int): Long {
            lastNCtx = nCtx
            return contextHandle
        }

        override fun freeContext(ctx: Long) {
            calls += "freeContext"
        }

        override fun freeModel(model: Long) {
            calls += "freeModel"
        }

        override fun score(
            ctx: Long,
            context: String,
            candidates: Array<String>,
            timeoutMs: Long,
        ): FloatArray? {
            calls += "score"
            return FloatArray(candidates.size) { -(it + 1).toFloat() }
        }

        override fun generate(ctx: Long, prompt: String, maxTokens: Int, timeoutMs: Long): String? {
            calls += "generate"
            return " mat."
        }

        override fun describe(model: Long): String = "fake"
    }

    @Test
    fun mmapOnlyAtAlignedOffsets() {
        assertThat(LlamaModel.canMmap(0)).isTrue()
        assertThat(LlamaModel.canMmap(32)).isTrue()
        assertThat(LlamaModel.canMmap(4096)).isTrue()
        assertThat(LlamaModel.canMmap(1)).isFalse()
        assertThat(LlamaModel.canMmap(31)).isFalse()
        assertThat(LlamaModel.canMmap(33)).isFalse()
        assertThat(LlamaModel.canMmap(-32)).isFalse()
    }

    @Test
    fun openFdChoosesMmapFromTheOffset() {
        val backend = FakeBackend()
        LlamaModel.openFd(backend, fd = 3, offset = 64).close()
        assertThat(backend.lastMmap).isTrue()
        LlamaModel.openFd(backend, fd = 3, offset = 70).close()
        assertThat(backend.lastMmap).isFalse()
        LlamaModel.openFile(backend, "/x.gguf").close()
        assertThat(backend.lastMmap).isTrue()
    }

    @Test
    fun failedLoadThrows() {
        val backend = FakeBackend(modelHandle = 0L)
        assertThrows(LlamaException::class.java) { LlamaModel.openFile(backend, "/x.gguf") }
        assertThrows(LlamaException::class.java) { LlamaModel.openFd(backend, 3, 0) }
    }

    @Test
    fun failedContextThrows() {
        val backend = FakeBackend(contextHandle = 0L)
        LlamaModel.openFile(backend, "/x.gguf").use { model ->
            assertThrows(LlamaException::class.java) { model.newSession() }
        }
    }

    @Test
    fun sessionDelegatesAndRejectsUseAfterClose() {
        val backend = FakeBackend()
        LlamaModel.openFile(backend, "/x.gguf").use { model ->
            val session = model.newSession()
            assertThat(session.score("the", listOf("a", "b"), 10)).isEqualTo(floatArrayOf(-1f, -2f))
            assertThat(session.generate("the", 3, 10)).isEqualTo(" mat.")
            session.close()
            session.close()
            assertThat(session.isClosed).isTrue()
            assertThrows(IllegalStateException::class.java) {
                session.score("the", listOf("a"), 10)
            }
            assertThrows(IllegalStateException::class.java) { session.generate("the", 3, 10) }
            assertThat(backend.calls.count { it == "freeContext" }).isEqualTo(1)
        }
    }

    @Test
    fun contextSizeIsCoercedToAMultipleOf256InRange() {
        assertThat(LlamaModel.coerceCtx(1)).isEqualTo(256)
        assertThat(LlamaModel.coerceCtx(256)).isEqualTo(256)
        assertThat(LlamaModel.coerceCtx(257)).isEqualTo(512)
        assertThat(LlamaModel.coerceCtx(300)).isEqualTo(512)
        assertThat(LlamaModel.coerceCtx(2048)).isEqualTo(2048)
        assertThat(LlamaModel.coerceCtx(5000)).isEqualTo(2048)
        assertThat(LlamaModel.coerceCtx(-5)).isEqualTo(256)
        val backend = FakeBackend()
        LlamaModel.openFile(backend, "/x.gguf").use { model ->
            model.newSession(nCtx = 300)
            assertThat(backend.lastNCtx).isEqualTo(512)
            model.newSession()
            assertThat(backend.lastNCtx).isEqualTo(LlamaModel.DEFAULT_CTX)
        }
    }

    @Test
    fun closingFromTwoThreadsFreesOnce() {
        val backend = FakeBackend()
        LlamaModel.openFile(backend, "/x.gguf").use { model ->
            val session = model.newSession()
            val threads = List(4) { Thread { session.close() } }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            assertThat(session.isClosed).isTrue()
            assertThat(backend.calls.count { it == "freeContext" }).isEqualTo(1)
        }
    }

    @Test
    fun emptyCandidatesNeedNoNativeCall() {
        val backend = FakeBackend()
        LlamaModel.openFile(backend, "/x.gguf").use { model ->
            val session = model.newSession()
            assertThat(session.score("the", emptyList(), 10)).isEmpty()
            assertThat(backend.calls).doesNotContain("score")
        }
    }

    @Test
    fun closingTheModelClosesItsSessionsFirst() {
        val backend = FakeBackend()
        val model = LlamaModel.openFile(backend, "/x.gguf")
        val session = model.newSession()
        model.close()
        model.close()
        assertThat(backend.calls).containsExactly("freeContext", "freeModel").inOrder()
        assertThat(session.isClosed).isTrue()
        assertThat(model.isClosed).isTrue()
        assertThrows(IllegalStateException::class.java) { model.newSession() }
        assertThrows(IllegalStateException::class.java) { model.description }
    }

    @Test
    fun closedSessionIsForgottenByItsModel() {
        val backend = FakeBackend()
        val model = LlamaModel.openFile(backend, "/x.gguf")
        model.newSession().close()
        model.close()
        // The session was already freed once; closing the model must not free it again.
        assertThat(backend.calls.count { it == "freeContext" }).isEqualTo(1)
    }
}

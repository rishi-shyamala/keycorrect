package dev.keycorrect.core.decoder

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.Capability
import dev.keycorrect.core.engineapi.ComposingInput
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.engineapi.PredictiveEngine
import dev.keycorrect.core.engineapi.TypingContext
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Test

/** Exercises the suspend [PredictiveEngine] surface without a coroutines library. */
class SuspendPredictTest {
    private val engine: PredictiveEngine = ClassicEngine(TestDictionary.dictionary)

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return result!!.getOrThrow()
    }

    @Test
    fun predictDelegatesToTheSynchronousPath() {
        val request =
            PredictRequest(
                TypingContext("", "", dev.keycorrect.core.engineapi.FieldClass.Text),
                TestKeyboard.typed("helli"),
                TestKeyboard.layout,
            )
        val result: PredictResult = runSuspend { engine.predict(request) }
        assertThat(result.autocorrect?.text).isEqualTo("hello")
        assertThat(result).isEqualTo((engine as ClassicEngine).predictNow(request))
    }

    @Test
    fun loadAndUnloadAreNoOpsAndInfoIsDescriptive() {
        runSuspend {
            engine.load()
            engine.unload()
        }
        assertThat(engine.info.id).isEqualTo(ClassicEngine.ID)
        assertThat(engine.info.capabilities).contains(Capability.Predict)
        assertThat(ComposingInput.Empty.text).isEmpty()
    }
}

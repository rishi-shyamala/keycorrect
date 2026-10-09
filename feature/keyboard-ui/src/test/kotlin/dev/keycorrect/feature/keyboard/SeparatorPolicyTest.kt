package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.prediction.Predictor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Test

/**
 * The separator policy of the decoder loop with a slow engine on a real decoder thread: the main
 * thread never enters the engine while a decode is in flight, waits briefly for it, and otherwise
 * commits the typed word as it is.
 */
class SeparatorPolicyTest {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val mainThread = Thread.currentThread()
    private val mainQueue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
    private val gate = CountDownLatch(1)
    private val started = CountDownLatch(1)

    @Volatile private var enteredOnMain = false

    /** An engine that blocks on [gate] for the word "teh" and answers "the". */
    private val slowPredictor = Predictor { request: PredictRequest, _ ->
        if (Thread.currentThread() === mainThread) enteredOnMain = true
        if (request.composing.text == "teh") {
            started.countDown()
            gate.await(5, TimeUnit.SECONDS)
        }
        ScriptedPredictor().run {
            word("teh", listOf("the"), autocorrect = "the")
            predict(request) { false }
        }
    }

    private fun controller(waitMs: Long): KeyboardController {
        backend.predictor = slowPredictor
        return KeyboardController(
            editor,
            KeyboardComposer(BuiltInLayouts.repository()),
            testEnv(
                backend,
                executor,
                postToMain = { mainQueue.add(it) },
                separatorWaitMs = waitMs,
            ),
        )
    }

    private fun drainMain() {
        while (true) (mainQueue.poll() ?: return).run()
    }

    @After
    fun tearDown() {
        gate.countDown()
        executor.shutdownNow()
    }

    private fun typeTeh(c: KeyboardController) {
        c.onStartInput(FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0), KeyboardSettings())
        "teh".forEach { c.onKey(KeyAction.Text("$it")) }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun aBusyEngineIsNeverEnteredOnTheMainThreadAndTheWordIsKeptAsTyped() {
        val c = controller(waitMs = 20)
        typeTeh(c)
        c.onKey(KeyAction.Space) // the decode for "teh" is still blocked inside the engine
        assertThat(editor.text.toString()).isEqualTo("teh ")
        assertThat(enteredOnMain).isFalse()
    }

    @Test
    fun aSeparatorWaitsBrieflyForTheOutstandingDecodeAndUsesItsResult() {
        val c = controller(waitMs = 5_000)
        typeTeh(c)
        Thread {
            Thread.sleep(50)
            gate.countDown()
        }
            .start()
        c.onKey(KeyAction.Space)
        assertThat(editor.text.toString()).isEqualTo("the ")
        assertThat(enteredOnMain).isFalse()
    }

    @Test
    fun aResultThatFinishedButWasNotDeliveredYetIsUsed() {
        val c = controller(waitMs = 20)
        typeTeh(c)
        gate.countDown()
        executor.submit {}.get(5, TimeUnit.SECONDS) // the decoder thread is idle again
        // The result is queued for the main thread but not delivered; the separator still has it.
        c.onKey(KeyAction.Space)
        assertThat(editor.text.toString()).isEqualTo("the ")
        assertThat(enteredOnMain).isFalse()
        drainMain() // the late delivery is dropped as stale
        assertThat(c.strip.isEmpty).isTrue()
    }
}

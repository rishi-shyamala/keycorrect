package dev.keycorrect.feature.keyboard.voice

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.TextEditor
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.voice.SttConfig
import dev.keycorrect.core.voice.SttEngine
import dev.keycorrect.core.voice.SttEngineInfo
import dev.keycorrect.core.voice.SttError
import dev.keycorrect.core.voice.SttListener
import dev.keycorrect.core.voice.SttResult
import dev.keycorrect.core.voice.SttSession
import dev.keycorrect.feature.keyboard.SimEditor
import org.junit.Test

class VoiceControllerTest {
    private class FakeSession : SttSession {
        var audio = 0
        var finished = false
        var cancelled = false

        override fun acceptAudio(pcm: ShortArray, length: Int) {
            audio += length
        }

        override fun finish() {
            finished = true
        }

        override fun cancel() {
            cancelled = true
        }
    }

    private class FakeEngine(ownAudio: Boolean = false) : SttEngine {
        override val info =
            SttEngineInfo("fake", "Fake", streaming = true, capturesOwnAudio = ownAudio)
        val session = FakeSession()
        var listener: SttListener? = null
        var released = false
        var config: SttConfig? = null

        override fun startSession(config: SttConfig, listener: SttListener): SttSession {
            this.config = config
            this.listener = listener
            return session
        }

        override fun release() {
            released = true
        }
    }

    private class FakeEnv(var engine: FakeEngine? = FakeEngine()) : VoiceEnvironment {
        var granted = true
        var requested = 0
        var captureOpen = false
        var captureFails = false
        var onAudio: ((ShortArray, Int) -> Unit)? = null
        var onLevel: ((Float) -> Unit)? = null

        override fun micPermissionGranted() = granted

        override fun requestMicPermission() {
            requested++
        }

        override fun openEngine(settings: KeyboardSettings, onLevel: (Float) -> Unit) = engine

        override fun startCapture(
            sampleRate: Int,
            onAudio: (ShortArray, Int) -> Unit,
            onLevel: (Float) -> Unit,
            onFailure: () -> Unit,
        ): CaptureHandle? {
            if (captureFails) return null
            captureOpen = true
            this.onAudio = onAudio
            this.onLevel = onLevel
            return CaptureHandle { captureOpen = false }
        }
    }

    private val editor = SimEditor()
    private val env = FakeEnv()
    private var settings = KeyboardSettings()
    private var sensitive = false
    private var settles = 0
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val voice =
        VoiceController(
            editor as TextEditor,
            env,
            { settings },
            { sensitive },
            { true },
            { settles++ },
            { it() },
            { delay, block ->
                val entry = delay to block
                timers += entry
                val cancel: () -> Unit = { timers.remove(entry) }
                cancel
            },
        )

    private fun partial(text: String, stable: Int = 0) =
        env.engine!!.listener!!.onResult(SttResult.Partial(text, stable))

    private fun final(text: String) = env.engine!!.listener!!.onResult(SttResult.Final(text))

    @Test
    fun startPartialFinalApplyEditsAndRelease() {
        val engine = env.engine!!
        voice.start()
        assertThat(voice.state).isEqualTo(VoiceState.Listening())
        assertThat(env.captureOpen).isTrue()
        env.onAudio!!(ShortArray(480), 480)
        assertThat(engine.session.audio).isEqualTo(480)
        partial("hello wor")
        assertThat(voice.state).isEqualTo(VoiceState.Listening(partial = "hello wor"))
        assertThat(editor.text.toString()).isEqualTo("Hello wor")
        voice.finish()
        final("hello world")
        assertThat(editor.text.toString()).isEqualTo("Hello world")
        assertThat(editor.compStart).isEqualTo(-1)
        assertThat(voice.state).isEqualTo(VoiceState.Idle)
        assertThat(engine.released).isTrue()
        assertThat(env.captureOpen).isFalse()
    }

    @Test
    fun levelFollowsTheCapture() {
        voice.start()
        env.onLevel!!(0.6f)
        assertThat(voice.level).isEqualTo(0.6f)
        voice.stopNow()
        assertThat(voice.level).isEqualTo(0f)
    }

    @Test
    fun cancelClearsTheComposingTextAndEndsTheSession() {
        voice.start()
        partial("some words")
        assertThat(editor.text.toString()).isNotEmpty()
        voice.cancel()
        assertThat(editor.text.toString()).isEmpty()
        assertThat(env.engine!!.session.cancelled).isTrue()
        assertThat(voice.state).isEqualTo(VoiceState.Idle)
        assertThat(env.engine!!.released).isTrue()
    }

    @Test
    fun stopWaitsForTheFinalResult() {
        voice.start()
        partial("good morning")
        voice.finish()
        assertThat(voice.state).isEqualTo(VoiceState.Listening("good morning", finishing = true))
        assertThat(env.captureOpen).isFalse()
        assertThat(env.engine!!.session.finished).isTrue()
        final("good morning everyone")
        assertThat(editor.text.toString()).isEqualTo("Good morning everyone")
        assertThat(voice.state).isEqualTo(VoiceState.Idle)
    }

    @Test
    fun aStopWithoutAFinalResultEndsAfterTheTimeoutKeepingTheText() {
        voice.start()
        partial("good morning")
        voice.finish()
        timers.single { it.first == 4_000L }.second()
        assertThat(voice.state).isEqualTo(VoiceState.Idle)
        assertThat(editor.text.toString()).isEqualTo("Good morning")
        assertThat(editor.compStart).isEqualTo(-1)
    }

    @Test
    fun aStreamingEngineKeepsListeningAfterAFinalUntilStop() {
        voice.start()
        final("first sentence.")
        assertThat(voice.state).isEqualTo(VoiceState.Listening())
        final("second one")
        assertThat(editor.text.toString()).isEqualTo("First sentence. Second one")
        voice.finish()
        final("")
        assertThat(voice.state).isEqualTo(VoiceState.Idle)
    }

    @Test
    fun aSingleShotRecognizerEndsWithItsFinalAndNeedsNoCapture() {
        val own = FakeEngine(ownAudio = true)
        env.engine = own
        voice.start()
        assertThat(env.captureOpen).isFalse()
        final("hello")
        assertThat(voice.state).isEqualTo(VoiceState.Idle)
        assertThat(own.released).isTrue()
    }

    @Test
    fun typingAKeyStopsDictationKeepingThePartialText() {
        voice.start()
        partial("hello there")
        voice.stopNow()
        assertThat(voice.state).isEqualTo(VoiceState.Idle)
        assertThat(editor.text.toString()).isEqualTo("Hello there")
        assertThat(editor.compStart).isEqualTo(-1)
        assertThat(env.engine!!.session.cancelled).isTrue()
        // A late result of the ended dictation changes nothing.
        env.engine!!.listener!!.onResult(SttResult.Final("ghost"))
        assertThat(editor.text.toString()).isEqualTo("Hello there")
    }

    @Test
    fun aFieldChangeOrCursorMoveStopsIt() {
        voice.start()
        voice.onExternalChange()
        assertThat(voice.state).isEqualTo(VoiceState.Idle)
        assertThat(env.captureOpen).isFalse()
    }

    @Test
    fun noEngineIsReportedAndClearsItself() {
        env.engine = null
        voice.start()
        assertThat(voice.state).isEqualTo(VoiceState.Failed(SttError.NoEngine))
        assertThat(voice.barVisible).isTrue()
        timers.single { it.first == 3_000L }.second()
        assertThat(voice.state).isEqualTo(VoiceState.Idle)
    }

    @Test
    fun withoutPermissionItAsksAndReportsIt() {
        env.granted = false
        voice.start()
        assertThat(env.requested).isEqualTo(1)
        assertThat(voice.state).isEqualTo(VoiceState.Failed(SttError.NoPermission))
        assertThat(env.engine!!.listener).isNull()
    }

    @Test
    fun aMicrophoneThatWontOpenFailsWithAudioAndReleasesTheEngine() {
        env.captureFails = true
        voice.start()
        assertThat(voice.state).isEqualTo(VoiceState.Failed(SttError.Audio))
        assertThat(env.engine!!.released).isTrue()
    }

    @Test
    fun anEngineErrorClearsTheComposingTextAndShowsTheMessage() {
        voice.start()
        partial("mumble")
        env.engine!!.listener!!.onResult(SttResult.Error(SttError.NoMatch))
        assertThat(editor.text.toString()).isEmpty()
        assertThat(voice.state).isEqualTo(VoiceState.Failed(SttError.NoMatch))
    }

    @Test
    fun neverStartsInASensitiveFieldOrWithVoiceOff() {
        sensitive = true
        voice.start()
        assertThat(voice.state).isEqualTo(VoiceState.Idle)
        assertThat(env.requested).isEqualTo(0)
        sensitive = false
        settings = KeyboardSettings(voiceEnabled = false)
        voice.start()
        assertThat(voice.state).isEqualTo(VoiceState.Idle)
        assertThat(editor.readCalls).isEqualTo(0)
    }

    @Test
    fun theAutoPunctuationSettingGoesToTheEngine() {
        settings = KeyboardSettings(voiceAutoPunctuation = false)
        voice.start()
        assertThat(env.engine!!.config!!.punctuate).isFalse()
    }

    @Test
    fun deleteThatRemovesTheLastUtteranceWhileItIsStillThere() {
        voice.start()
        final("hello world")
        final("delete that")
        assertThat(editor.text.toString()).isEmpty()
    }

    @Test
    fun toggleStopsARunningDictation() {
        voice.toggle()
        assertThat(voice.dictating).isTrue()
        voice.toggle()
        assertThat(env.engine!!.session.finished).isTrue()
    }
}

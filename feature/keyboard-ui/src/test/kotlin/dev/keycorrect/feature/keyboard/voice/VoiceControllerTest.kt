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
        override var busy = false
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
        var unavailable: SttError? = null
        var privateSeen: Boolean? = null
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

        override fun openEngine(
            settings: KeyboardSettings,
            privateField: Boolean,
            onLevel: (Float) -> Unit,
        ): EngineOpening {
            privateSeen = privateField
            unavailable?.let {
                return EngineOpening.Unavailable(it)
            }
            return engine?.let { EngineOpening.Ready(it) }
                ?: EngineOpening.Unavailable(SttError.NoEngine)
        }

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
    private var private = false
    private var settles = 0
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private var now = 0L
    private var tickBlock: (() -> Unit)? = null
    private val voice =
        VoiceController(
            editor as TextEditor,
            env,
            { settings },
            { sensitive },
            { private },
            { true },
            { settles++ },
            { it() },
            { delay, block ->
                val entry = delay to block
                timers += entry
                val cancel: () -> Unit = { timers.remove(entry) }
                cancel
            },
            { _, block ->
                tickBlock = block
                val cancel: () -> Unit = { tickBlock = null }
                cancel
            },
            { now },
        )

    /** Moves the clock to [ms] and runs the tick that is due. */
    private fun tickAt(ms: Long) {
        now = ms
        tickBlock?.invoke()
    }

    private fun progress(phrase: Long, limit: Long = 7000, speaking: Boolean = true) =
        env.engine!!.listener!!.onResult(SttResult.Progress(phrase, limit, speaking))

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
        assertThat(voice.state)
            .isEqualTo(VoiceState.Listening(partial = "hello wor", stableLength = 0))
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
        assertThat(voice.state).isEqualTo(VoiceState.Failed(SttError.Timeout))
        assertThat(editor.text.toString()).isEqualTo("Good morning")
        assertThat(editor.compStart).isEqualTo(-1)
    }

    @Test
    fun aBusyEngineGetsMoreTimeThenAMessageReplacesTheSilentDrop() {
        voice.start()
        partial("good morning")
        env.engine!!.session.busy = true
        voice.finish()
        // 4 s: still decoding, so it waits again (1 s at a time) up to 15 s in all.
        var waited = 4_000L
        timers.single { it.first == 4_000L }.second()
        assertThat(voice.dictating).isTrue()
        timers.removeAll { it.first == 4_000L }
        while (waited < 15_000L) {
            val (delay, block) = timers.single()
            assertThat(delay).isEqualTo(1_000L)
            timers.clear()
            waited += delay
            block()
            if (waited < 15_000L) assertThat(voice.dictating).isTrue()
        }
        assertThat(voice.state).isEqualTo(VoiceState.Failed(SttError.Timeout))
        assertThat(editor.text.toString()).isEqualTo("Good morning")
    }

    @Test
    fun theFinalArrivingDuringTheExtraWaitStillCountsAndCancelsTheTimer() {
        voice.start()
        partial("good morning")
        env.engine!!.session.busy = true
        voice.finish()
        timers.single { it.first == 4_000L }.second()
        final("good morning everyone")
        assertThat(editor.text.toString()).isEqualTo("Good morning everyone")
        assertThat(voice.state).isEqualTo(VoiceState.Idle)
        assertThat(timers.none { it.first == 1_000L }).isTrue()
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
    fun voicePackProblemsAreShownAsTheyAre() {
        for (error in
            listOf(
                SttError.NoVoicePack,
                SttError.VoicePackUntrusted,
                SttError.VoicePackBroken,
                SttError.PrivateField,
            )) {
            env.unavailable = error
            voice.start()
            assertThat(voice.state).isEqualTo(VoiceState.Failed(error))
            voice.clearFailure()
            assertThat(env.engine!!.listener).isNull()
        }
    }

    @Test
    fun theEnvironmentIsToldWhenTheFieldIsPrivate() {
        voice.start()
        assertThat(env.privateSeen).isFalse()
        voice.stopNow()
        private = true
        voice.start()
        assertThat(env.privateSeen).isTrue()
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
    fun deleteThatInTheNextSessionRemovesThePreviousDictationIfItIsStillThere() {
        voice.start()
        final("hello world")
        voice.finish()
        final("")
        assertThat(editor.text.toString()).isEqualTo("Hello world")
        env.engine = FakeEngine()
        voice.start()
        final("delete that")
        assertThat(editor.text.toString()).isEmpty()
    }

    @Test
    fun deleteThatInTheNextSessionLeavesTextTheUserChangedAlone() {
        voice.start()
        final("hello world")
        voice.finish()
        final("")
        editor.commitText(" more", 1) // typed since: the dictation is no longer at the cursor
        env.engine = FakeEngine()
        voice.start()
        final("delete that")
        assertThat(editor.text.toString()).isEqualTo("Hello world more")
    }

    @Test
    fun moonshineStyleDeleteThatRemovesTheSegmentBeforeIt() {
        voice.start()
        env.engine!!.listener!!.onResult(SttResult.Partial("Hello there.", 12, endsSegment = true))
        env.engine!!
            .listener!!
            .onResult(SttResult.Partial("Hello there. Second thing.", 26, endsSegment = true))
        assertThat(editor.text.toString()).isEqualTo("Hello there. Second thing.")
        env.engine!!
            .listener!!
            .onResult(
                SttResult.Partial("Hello there. Second thing. Delete that.", 39, endsSegment = true)
            )
        assertThat(editor.text.toString()).isEqualTo("Hello there.")
    }

    @Test
    fun toggleStopsARunningDictation() {
        voice.toggle()
        assertThat(voice.dictating).isTrue()
        voice.toggle()
        assertThat(env.engine!!.session.finished).isTrue()
    }

    @Test
    fun theLimitsGoToTheEngine() {
        settings =
            KeyboardSettings(
                voicePhraseMs = 5000,
                voicePauseMs = 900,
                voiceLiveMs = KeyboardSettings.VOICE_LIVE_OFF,
            )
        voice.start()
        val c = env.engine!!.config!!
        assertThat(c.phraseLimitMillis).isEqualTo(5000)
        assertThat(c.pauseMillis).isEqualTo(900)
        assertThat(c.liveUpdateMillis).isEqualTo(SttConfig.LIVE_OFF)
    }

    @Test
    fun partialsKeepTheirStableLengthForTheGreyedTail() {
        voice.start()
        partial("hello world", stable = 5)
        assertThat((voice.state as VoiceState.Listening).stableLength).isEqualTo(5)
        partial("hello there", stable = 99)
        assertThat((voice.state as VoiceState.Listening).stableLength).isEqualTo(11)
    }

    @Test
    fun theTimerCountsWholeSecondsAndFormatsAsMinutesAndSeconds() {
        voice.start()
        assertThat(voice.elapsedSeconds).isEqualTo(0)
        tickAt(1500)
        assertThat(voice.elapsedSeconds).isEqualTo(1)
        tickAt(65_000)
        assertThat(voice.elapsedSeconds).isEqualTo(65)
        assertThat(VoiceController.formatElapsed(65)).isEqualTo("1:05")
        assertThat(VoiceController.formatElapsed(7)).isEqualTo("0:07")
        voice.stopNow()
        assertThat(voice.elapsedSeconds).isEqualTo(0)
        assertThat(tickBlock).isNull()
    }

    @Test
    fun theRingFollowsTheProgressAndTheHintAppearsAtSixtyPercent() {
        voice.start()
        progress(0)
        assertThat(voice.phraseFraction).isEqualTo(0f)
        assertThat(voice.phraseHint).isFalse()
        progress(3500)
        assertThat(voice.phraseFraction).isEqualTo(0.5f)
        assertThat(voice.phraseHint).isFalse()
        progress(4200)
        assertThat(voice.phraseHint).isTrue()
        assertThat(voice.phraseFraction).isLessThan(VoiceController.AMBER_FROM)
        progress(5600)
        assertThat(voice.phraseFraction).isAtLeast(VoiceController.AMBER_FROM)
        progress(9000) // never past a full ring
        assertThat(voice.phraseFraction).isEqualTo(1f)
        progress(100, speaking = false)
        assertThat(voice.speaking).isFalse()
        assertThat(voice.phraseHint).isFalse()
        assertThat(VoiceController.fractionOf(100, 0)).isEqualTo(0f)
    }

    @Test
    fun progressDoesNotTouchTheEditorOrTheText() {
        voice.start()
        progress(2000)
        assertThat(editor.text.toString()).isEmpty()
        assertThat(voice.state).isEqualTo(VoiceState.Listening())
    }

    @Test
    fun dictationStopsAfterTheSilenceLimitWithACountdownInTheLastSeconds() {
        settings = KeyboardSettings(voiceAutoStopSeconds = 10)
        voice.start()
        partial("hi there")
        now = 0
        progress(0, speaking = false)
        tickAt(6000)
        assertThat(voice.silenceCountdown).isEqualTo(0)
        tickAt(7500)
        assertThat(voice.silenceCountdown).isEqualTo(3)
        tickAt(9000)
        assertThat(voice.silenceCountdown).isEqualTo(1)
        // Speaking again starts the count from zero.
        now = 9500
        progress(500, speaking = true)
        tickAt(10_000)
        assertThat(voice.silenceCountdown).isEqualTo(0)
        assertThat(env.engine!!.session.finished).isFalse()
        tickAt(19_500)
        assertThat(env.engine!!.session.finished).isTrue()
        assertThat((voice.state as VoiceState.Listening).finishing).isTrue()
    }

    @Test
    fun autoStopOffOrAnEngineWithoutProgressNeverStops() {
        settings = KeyboardSettings(voiceAutoStopSeconds = KeyboardSettings.VOICE_AUTO_STOP_OFF)
        voice.start()
        progress(0, speaking = false)
        tickAt(600_000)
        assertThat(env.engine!!.session.finished).isFalse()
        voice.stopNow()

        settings = KeyboardSettings(voiceAutoStopSeconds = 5)
        env.engine = FakeEngine()
        voice.start()
        tickAt(1_200_000) // no progress was ever reported (the phone recognizer)
        assertThat(env.engine!!.session.finished).isFalse()
    }

    @Test
    fun unclearShowsItsOwnMessage() {
        assertThat(errorMessage(SttError.Unclear)).isNotEqualTo(errorMessage(SttError.NoMatch))
        voice.start()
        env.engine!!.listener!!.onResult(SttResult.Error(SttError.Unclear))
        assertThat(voice.state).isEqualTo(VoiceState.Failed(SttError.Unclear))
    }
}

package dev.keycorrect.voice

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.VoiceEngineChoice
import dev.keycorrect.core.voice.SttConfig
import dev.keycorrect.core.voice.SttEngine
import dev.keycorrect.core.voice.SttEngineInfo
import dev.keycorrect.core.voice.SttError
import dev.keycorrect.core.voice.SttListener
import dev.keycorrect.core.voice.SttSession
import dev.keycorrect.feature.keyboard.voice.EngineOpening
import dev.keycorrect.feature.voicesherpa.VoicePackProblem
import org.junit.Test

class ChooseEngineTest {
    private class Engine(id: String) : SttEngine {
        override val info = SttEngineInfo(id, id, streaming = false)

        override fun startSession(config: SttConfig, listener: SttListener): SttSession =
            error("unused")
    }

    private val pack = Engine("pack")
    private val phoneEngine = Engine("phone")
    private var packOpens = 0
    private var phoneOpens = 0

    private fun builtIn(result: BuiltInOpening) =
        object : BuiltInEngineSource {
            override fun open(): BuiltInOpening {
                packOpens++
                return result
            }
        }

    private val phone = PhoneEngineSource {
        phoneOpens++
        phoneEngine
    }

    private fun choose(
        choice: VoiceEngineChoice,
        builtIn: BuiltInEngineSource?,
        privateField: Boolean = false,
        phone: PhoneEngineSource = this.phone,
    ) = chooseEngine(choice, privateField, builtIn, phone) {}

    @Test
    fun builtInUsesThePack() {
        val opened = choose(VoiceEngineChoice.BuiltIn, builtIn(BuiltInOpening.Ready(pack)))
        assertThat(opened).isEqualTo(EngineOpening.Ready(pack))
        assertThat(phoneOpens).isEqualTo(0)
    }

    @Test
    fun builtInNeverFallsBackToThePhone() {
        val cases =
            mapOf(
                VoicePackProblem.NotInstalled to SttError.NoVoicePack,
                VoicePackProblem.SignatureMismatch to SttError.VoicePackUntrusted,
                VoicePackProblem.Broken to SttError.VoicePackBroken,
            )
        for ((problem, error) in cases) {
            val opened =
                choose(VoiceEngineChoice.BuiltIn, builtIn(BuiltInOpening.Unavailable(problem)))
            assertThat(opened).isEqualTo(EngineOpening.Unavailable(error))
        }
        assertThat(choose(VoiceEngineChoice.BuiltIn, null))
            .isEqualTo(EngineOpening.Unavailable(SttError.NoVoicePack))
        assertThat(phoneOpens).isEqualTo(0)
    }

    @Test
    fun theDefaultIsBuiltIn() {
        assertThat(KeyboardSettings().voiceEngine).isEqualTo(VoiceEngineChoice.BuiltIn)
    }

    @Test
    fun phoneRecognizerIsUsedOnlyWhenChosenAndNeverTouchesThePack() {
        val opened = choose(VoiceEngineChoice.PhoneRecognizer, builtIn(BuiltInOpening.Ready(pack)))
        assertThat(opened).isEqualTo(EngineOpening.Ready(phoneEngine))
        assertThat(packOpens).isEqualTo(0)
    }

    @Test
    fun phoneRecognizerWithoutOneOnThePhoneIsNoEngine() {
        val opened = choose(VoiceEngineChoice.PhoneRecognizer, null, phone = { null })
        assertThat(opened).isEqualTo(EngineOpening.Unavailable(SttError.NoEngine))
    }

    @Test
    fun phoneRecognizerIsNeverUsedInAPrivateField() {
        val opened =
            choose(
                VoiceEngineChoice.PhoneRecognizer,
                builtIn(BuiltInOpening.Ready(pack)),
                privateField = true,
            )
        assertThat(opened).isEqualTo(EngineOpening.Unavailable(SttError.PrivateField))
        assertThat(phoneOpens).isEqualTo(0)
        assertThat(packOpens).isEqualTo(0)
    }

    @Test
    fun theBuiltInEngineStillWorksInAPrivateField() {
        val opened =
            choose(
                VoiceEngineChoice.BuiltIn,
                builtIn(BuiltInOpening.Ready(pack)),
                privateField = true,
            )
        assertThat(opened).isEqualTo(EngineOpening.Ready(pack))
    }

    @Test
    fun theBackgroundWarmUpNeedsVoiceOnAndTheBuiltInEngine() {
        assertThat(warmUpWanted(KeyboardSettings())).isTrue()
        assertThat(warmUpWanted(KeyboardSettings(voiceEnabled = false))).isFalse()
        assertThat(warmUpWanted(KeyboardSettings(voiceEngine = VoiceEngineChoice.PhoneRecognizer)))
            .isFalse()
    }
}

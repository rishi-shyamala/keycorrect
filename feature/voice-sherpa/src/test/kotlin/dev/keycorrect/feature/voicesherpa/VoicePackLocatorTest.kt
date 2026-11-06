package dev.keycorrect.feature.voicesherpa

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.packs.PackProblem
import org.junit.Test

/** The voice-specific part of [VoicePackLocator]; the checks themselves are in `:core:packs`. */
class VoicePackLocatorTest {
    @Test
    fun theVoiceSpecNamesTheVoicePackInLoadOrder() {
        assertThat(VOICE_PACK_SPEC.packageName).isEqualTo("dev.keycorrect.voice")
        assertThat(VOICE_PACK_SPEC.libraries)
            .containsExactly("libonnxruntime.so", "libsherpa-onnx-jni.so")
            .inOrder()
        assertThat(VOICE_PACK_SPEC.assets)
            .containsExactly(
                "moonshine/encoder_model.ort",
                "moonshine/decoder_model_merged.ort",
                "moonshine/tokens.txt",
                "silero_vad.onnx",
            )
    }

    @Test
    fun everyProblemKeepsItsMeaningAndOnlyTheAssetsAreCalledModels() {
        PackProblem.entries.forEach { problem ->
            val expected =
                if (problem == PackProblem.AssetsMissing) VoicePackProblem.ModelsMissing
                else VoicePackProblem.valueOf(problem.name)
            assertThat(problem.toVoice()).isEqualTo(expected)
        }
        assertThat(PackProblem.entries.map { it.toVoice() })
            .containsExactlyElementsIn(VoicePackProblem.entries)
    }
}

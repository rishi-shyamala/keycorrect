package dev.keycorrect.settings

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.R
import dev.keycorrect.core.settings.KeyboardSettings
import org.junit.Test

class EngineApprovalSettingsTest {
    private val plugin = "p.a/p.a.Svc"

    @Test
    fun approvingPinsTheCertificateAndSelectsThePlugin() {
        val s = KeyboardSettings().withApproval(plugin, "c1")

        assertThat(s.neuralEngine).isEqualTo(plugin)
        assertThat(s.approvedEngines).containsExactly("$plugin|c1")
    }

    @Test
    fun removingTheApprovalOfTheChosenPluginSelectsBuiltin() {
        val s = KeyboardSettings().withApproval(plugin, "c1").withoutApproval(plugin)

        assertThat(s.neuralEngine).isEqualTo(KeyboardSettings.ENGINE_BUILTIN)
        assertThat(s.approvedEngines).isEmpty()
    }

    @Test
    fun removingAnotherPluginsApprovalKeepsTheChoice() {
        val s =
            KeyboardSettings()
                .withApproval("q/q.Svc", "c2")
                .withApproval(plugin, "c1")
                .withoutApproval("q/q.Svc")

        assertThat(s.neuralEngine).isEqualTo(plugin)
        assertThat(s.approvedEngines).containsExactly("$plugin|c1")
    }

    @Test
    fun theStrengthSliderSnapsToFiveHundredths() {
        assertThat(snapStrength(0.52f)).isWithin(1e-6f).of(0.5f)
        assertThat(snapStrength(0.53f)).isWithin(1e-6f).of(0.55f)
        assertThat(snapStrength(-1f)).isEqualTo(0f)
        assertThat(snapStrength(2f)).isEqualTo(1f)
    }

    @Test
    fun engineFailuresGetPlainMessages() {
        assertThat(errorMessage("OutOfMemory")).isEqualTo(R.string.lm_status_error_memory)
        assertThat(errorMessage("Whatever")).isEqualTo(R.string.lm_status_error_other)
    }
}

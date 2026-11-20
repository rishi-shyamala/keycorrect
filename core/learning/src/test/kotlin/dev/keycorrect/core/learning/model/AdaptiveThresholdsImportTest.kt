package dev.keycorrect.core.learning.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdaptiveThresholdsImportTest {
    @Test
    fun badRowsAreDroppedSoTheGateStaysFinite() {
        val t = AdaptiveThresholds(PersonalModelConfig())
        t.importRows(
            listOf(
                OutcomeRow(1, 1, 0, 0),
                OutcomeRow(1, 2, -3, 1),
                OutcomeRow(7, 3, 1, 1),
                OutcomeRow(1, 4, 4, 9),
            )
        )
        assertThat(t.phrase).hasSize(1)
        assertThat(t.phrase.single().accepted).isEqualTo(4)
        assertThat(t.acceptanceRate().isFinite()).isTrue()
        assertThat(t.autocorrect).isEmpty()
    }
}

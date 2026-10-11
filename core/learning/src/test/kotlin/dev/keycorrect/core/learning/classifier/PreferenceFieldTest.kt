package dev.keycorrect.core.learning.classifier

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.LearningUpdate.Preference
import org.junit.Test

class PreferenceFieldTest {
    @Test
    fun preferencesCarryTheEventsFieldClass() {
        val e =
            committed(
                "teh",
                "teh",
                method = CommitMethod.Typed,
                shown = listOf(seen("the")),
                field = FieldClass.PersonName,
            )
        val prefs = OutcomeClassifier().classify(e).filterIsInstance<Preference>()
        assertThat(prefs).isNotEmpty()
        assertThat(prefs.map { it.field }.toSet()).containsExactly(FieldClass.PersonName)
    }
}

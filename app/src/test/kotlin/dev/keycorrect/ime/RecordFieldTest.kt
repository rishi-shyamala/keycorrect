package dev.keycorrect.ime

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordFieldTest {
    private val own = "dev.keycorrect"

    @Test
    fun ownSettingsWindowWithNoInputTypeIsNotRecorded() {
        assertThat(shouldRecordField(InputType.TYPE_NULL, own, own)).isFalse()
    }

    @Test
    fun aRealFieldInThisAppIsRecorded() {
        assertThat(shouldRecordField(InputType.TYPE_CLASS_TEXT, own, own)).isTrue()
    }

    @Test
    fun aNoTypeFieldOfAnotherAppIsRecorded() {
        assertThat(shouldRecordField(InputType.TYPE_NULL, "com.example.term", own)).isTrue()
        assertThat(shouldRecordField(InputType.TYPE_NULL, null, own)).isTrue()
    }
}

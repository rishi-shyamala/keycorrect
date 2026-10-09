package dev.keycorrect.core.input

import android.view.inputmethod.EditorInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EnterActionTest {
    @Test
    fun unspecifiedActionInsertsNewLine() {
        assertThat(EnterAction.from(EditorInfo.IME_ACTION_UNSPECIFIED))
            .isEqualTo(EnterAction.NewLine)
    }

    @Test
    fun searchActionIsPerformed() {
        assertThat(EnterAction.from(EditorInfo.IME_ACTION_SEARCH))
            .isEqualTo(EnterAction.Editor(EditorInfo.IME_ACTION_SEARCH))
    }

    @Test
    fun noEnterActionFlagWinsOverAction() {
        val options = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertThat(EnterAction.from(options)).isEqualTo(EnterAction.NewLine)
    }
}

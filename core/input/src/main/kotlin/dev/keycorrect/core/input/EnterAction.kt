package dev.keycorrect.core.input

import android.view.inputmethod.EditorInfo

/** What the Enter key does in the current field. */
sealed interface EnterAction {
    /** Insert a line break. */
    data object NewLine : EnterAction

    /** Perform an editor action such as Go, Search, Send, Next or Done. */
    data class Editor(val actionId: Int) : EnterAction

    companion object {
        /** Resolves the Enter behaviour from [EditorInfo.imeOptions]. */
        fun from(imeOptions: Int): EnterAction {
            if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return NewLine
            return when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED,
                EditorInfo.IME_ACTION_NONE -> NewLine
                else -> Editor(action)
            }
        }
    }
}

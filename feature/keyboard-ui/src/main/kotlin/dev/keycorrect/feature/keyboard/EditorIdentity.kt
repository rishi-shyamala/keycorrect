package dev.keycorrect.feature.keyboard

import android.view.inputmethod.EditorInfo

/**
 * What identifies the focused field across `onStartInput` calls: the [EditorInfo] fields an app
 * would change when it moves to a different field, as `LatinIME` compares them. Plain ints and
 * strings so the comparison is testable without the platform class.
 */
data class EditorIdentity(
    val fieldId: Int,
    val packageName: String?,
    val inputType: Int,
    val imeOptions: Int,
    val privateImeOptions: String?,
) {
    /**
     * Whether an input start with this identity continues the field [previous] was started for. The
     * platform's `restarting` flag alone is not enough: an app can restart input for a different
     * field or with different options without it being a new session, and then the layer and shift
     * of the old field must not carry over (nor its autofill suggestions).
     */
    fun continues(previous: EditorIdentity?, restarting: Boolean): Boolean =
        restarting && previous == this

    companion object {
        fun from(info: EditorInfo): EditorIdentity =
            EditorIdentity(
                fieldId = info.fieldId,
                packageName = info.packageName,
                inputType = info.inputType,
                imeOptions = info.imeOptions,
                privateImeOptions = info.privateImeOptions,
            )
    }
}

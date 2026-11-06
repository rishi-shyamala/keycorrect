package dev.keycorrect.baselineprofile

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout

/** A bare EditText the benchmarks type into. */
class HostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val field =
            EditText(this).apply {
                contentDescription = FIELD_DESCRIPTION
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            }
        setContentView(LinearLayout(this).apply { addView(field) })
        field.requestFocus()
    }

    companion object {
        const val FIELD_DESCRIPTION = "benchmark-field"
    }
}

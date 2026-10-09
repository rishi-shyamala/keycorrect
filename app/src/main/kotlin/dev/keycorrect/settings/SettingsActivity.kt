package dev.keycorrect.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.keycorrect.appContainer
import dev.keycorrect.core.designsystem.KeyCorrectTheme

/** Launcher entry point: hosts the settings UI (see [SettingsApp]). */
class SettingsActivity : ComponentActivity() {
    /** Bumped whenever IME status may have changed (resume, picker dialog closed). */
    private var refreshKey by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KeyCorrectTheme {
                SettingsApp(
                    container = appContainer,
                    status = imeStatus(refreshKey),
                    onOpenKeyboardSettings = {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    },
                    onChooseKeyboard = {
                        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshKey++
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshKey++
    }

    @Suppress("UNUSED_PARAMETER") // refreshKey forces re-evaluation on change.
    private fun imeStatus(refreshKey: Int): ImeStatus {
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current =
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val selected = current?.substringBefore('/') == packageName
        return ImeStatus(enabled = enabled, selected = selected)
    }
}

data class ImeStatus(val enabled: Boolean, val selected: Boolean)

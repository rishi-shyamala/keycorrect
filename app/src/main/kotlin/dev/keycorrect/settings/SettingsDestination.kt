package dev.keycorrect.settings

import androidx.annotation.StringRes
import dev.keycorrect.BuildConfig
import dev.keycorrect.R

/**
 * Screens reachable inside [SettingsActivity]. [route] is the stable id used to save the back
 * stack.
 */
sealed class SettingsDestination(val route: String, @StringRes val titleRes: Int) {
    data object Home : SettingsDestination("home", R.string.app_name)

    data object Setup : SettingsDestination("setup", R.string.setup_title)

    data object Keyboard : SettingsDestination("keyboard", R.string.keyboard_title)

    data object Feedback : SettingsDestination("feedback", R.string.feedback_title)

    data object About : SettingsDestination("about", R.string.about_title)

    /** Debug-only screen for checking inline autofill with a real password manager. */
    data object AutofillTest : SettingsDestination("autofill_test", R.string.autofill_test_title)

    companion object {
        private val all by lazy {
            listOf(Home, Setup, Keyboard, Feedback, About) +
                if (BuildConfig.DEBUG) listOf(AutofillTest) else emptyList()
        }

        fun fromRoute(route: String): SettingsDestination =
            all.firstOrNull { it.route == route } ?: Home
    }
}

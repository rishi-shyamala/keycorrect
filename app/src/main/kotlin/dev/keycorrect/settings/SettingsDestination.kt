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

    data object Editing : SettingsDestination("editing", R.string.editing_title)

    data object Emoji : SettingsDestination("emoji", R.string.emoji_title)

    data object Clipboard : SettingsDestination("clipboard", R.string.clipboard_title)

    data object TextCorrection :
        SettingsDestination("text_correction", R.string.text_correction_title)

    data object PersonalDictionary :
        SettingsDestination("personal_dictionary", R.string.dictionary_title)

    data object Shortcuts : SettingsDestination("shortcuts", R.string.shortcuts_title)

    data object Learning : SettingsDestination("learning", R.string.learning_title)

    data object LearnedWords : SettingsDestination("learning_words", R.string.learning_words_title)

    data object LearnedCorrections :
        SettingsDestination("learning_corrections", R.string.learning_corrections_title)

    data object NeverCorrected :
        SettingsDestination("learning_never_corrected", R.string.learning_never_corrected_title)

    data object NeverSuggested :
        SettingsDestination("learning_never_suggested", R.string.learning_never_suggested_title)

    data object Feedback : SettingsDestination("feedback", R.string.feedback_title)

    data object Advanced : SettingsDestination("advanced", R.string.advanced_title)

    data object About : SettingsDestination("about", R.string.about_title)

    data object Licenses : SettingsDestination("licenses", R.string.licenses_title)

    /** Debug-only screen for checking inline autofill with a real password manager. */
    data object AutofillTest : SettingsDestination("autofill_test", R.string.autofill_test_title)

    companion object {
        private val all by lazy {
            listOf(
                Home,
                Setup,
                Keyboard,
                Editing,
                Emoji,
                Clipboard,
                TextCorrection,
                PersonalDictionary,
                Shortcuts,
                Learning,
                LearnedWords,
                LearnedCorrections,
                NeverCorrected,
                NeverSuggested,
                Feedback,
                Advanced,
                About,
                Licenses,
            ) + if (BuildConfig.DEBUG) listOf(AutofillTest) else emptyList()
        }

        fun fromRoute(route: String): SettingsDestination =
            all.firstOrNull { it.route == route } ?: Home
    }
}

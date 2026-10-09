package dev.keycorrect.core.input

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FieldProfileTest {
    private val text = InputType.TYPE_CLASS_TEXT

    private fun profile(inputType: Int, imeOptions: Int = 0) =
        FieldProfile.from(inputType, imeOptions)

    @Test
    fun textVariationsMapToKinds() {
        val cases =
            mapOf(
                InputType.TYPE_TEXT_VARIATION_NORMAL to FieldKind.Text,
                InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT to FieldKind.Text,
                InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE to FieldKind.Text,
                InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE to FieldKind.Text,
                InputType.TYPE_TEXT_VARIATION_FILTER to FieldKind.Text,
                InputType.TYPE_TEXT_VARIATION_PHONETIC to FieldKind.Text,
                InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT to FieldKind.Text,
                InputType.TYPE_TEXT_VARIATION_PASSWORD to FieldKind.Password,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD to FieldKind.Password,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD to FieldKind.VisiblePassword,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS to FieldKind.Email,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS to FieldKind.Email,
                InputType.TYPE_TEXT_VARIATION_URI to FieldKind.Url,
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME to FieldKind.PersonName,
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS to FieldKind.PostalAddress,
            )
        for ((variation, kind) in cases) {
            assertThat(profile(text or variation).kind).isEqualTo(kind)
        }
    }

    @Test
    fun nonTextClassesMapToKinds() {
        assertThat(profile(InputType.TYPE_CLASS_NUMBER).kind).isEqualTo(FieldKind.Number)
        assertThat(profile(InputType.TYPE_CLASS_PHONE).kind).isEqualTo(FieldKind.Phone)
        assertThat(profile(InputType.TYPE_CLASS_DATETIME).kind).isEqualTo(FieldKind.DateTime)
    }

    @Test
    fun unknownClassFallsBackToText() {
        // Class 5 is undefined; its variation bits must not be read as a text variation.
        val p = profile(0x5 or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertThat(p.kind).isEqualTo(FieldKind.Text)
        assertThat(p.sensitive).isFalse()
    }

    @Test
    fun typeNullIsTerminal() {
        val p = profile(InputType.TYPE_NULL, EditorInfo.IME_ACTION_SEND)
        assertThat(p.kind).isEqualTo(FieldKind.Terminal)
        assertThat(p.enterAction).isEqualTo(EnterAction.NewLine)
        assertThat(p.suggestionsEnabled).isFalse()
        assertThat(p.autocorrectEnabled).isFalse()
        assertThat(p.learningEnabled).isFalse()
        assertThat(p.sensitive).isFalse()
        assertThat(p.multiLine).isFalse()
        assertThat(p.autoCapitalize).isEqualTo(AutoCapitalize.None)
    }

    @Test
    fun plainTextDefaults() {
        val p = profile(text)
        assertThat(p)
            .isEqualTo(
                FieldProfile(
                    kind = FieldKind.Text,
                    enterAction = EnterAction.NewLine,
                    autoCapitalize = AutoCapitalize.None,
                    multiLine = false,
                    suggestionsEnabled = true,
                    autocorrectEnabled = true,
                    learningEnabled = true,
                    sensitive = false,
                    signedNumber = false,
                    decimalNumber = false,
                )
            )
    }

    @Test
    fun passwordVariationsAreSensitiveAndOff() {
        val inputs =
            listOf(
                text or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            )
        for (input in inputs) {
            val p = profile(input)
            assertThat(p.sensitive).isTrue()
            assertThat(p.suggestionsEnabled).isFalse()
            assertThat(p.autocorrectEnabled).isFalse()
            assertThat(p.learningEnabled).isFalse()
        }
    }

    @Test
    fun numberPasswordKeepsNumberKind() {
        val p = profile(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        assertThat(p.kind).isEqualTo(FieldKind.Number)
        assertThat(p.sensitive).isTrue()
    }

    @Test
    fun plainNumberIsNotSensitive() {
        assertThat(profile(InputType.TYPE_CLASS_NUMBER).sensitive).isFalse()
        assertThat(
                profile(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL)
                    .sensitive
            )
            .isFalse()
    }

    @Test
    fun emailAndUrlSuggestWithoutAutocorrect() {
        val inputs =
            listOf(
                text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                text or InputType.TYPE_TEXT_VARIATION_URI,
            )
        for (input in inputs) {
            val p = profile(input)
            assertThat(p.suggestionsEnabled).isTrue()
            assertThat(p.autocorrectEnabled).isFalse()
            assertThat(p.learningEnabled).isTrue()
            assertThat(p.sensitive).isFalse()
        }
    }

    @Test
    fun numberPhoneDateTimeAreInert() {
        val inputs =
            listOf(
                InputType.TYPE_CLASS_NUMBER,
                InputType.TYPE_CLASS_PHONE,
                InputType.TYPE_CLASS_DATETIME,
                InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE,
                InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME,
            )
        for (input in inputs) {
            val p = profile(input)
            assertThat(p.suggestionsEnabled).isFalse()
            assertThat(p.autocorrectEnabled).isFalse()
            assertThat(p.learningEnabled).isFalse()
        }
    }

    @Test
    fun personNameAndPostalAddressStaySuggestive() {
        for (variation in
            listOf(
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
            )) {
            val p = profile(text or variation)
            assertThat(p.suggestionsEnabled).isTrue()
            assertThat(p.autocorrectEnabled).isTrue()
            assertThat(p.learningEnabled).isTrue()
        }
    }

    @Test
    fun noSuggestionsFlagDisablesSuggestionsAndAutocorrect() {
        val p = profile(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        assertThat(p.suggestionsEnabled).isFalse()
        assertThat(p.autocorrectEnabled).isFalse()
        assertThat(p.learningEnabled).isTrue()
    }

    @Test
    fun noSuggestionsFlagOverridesEmailSuggestions() {
        val p =
            profile(
                text or
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            )
        assertThat(p.kind).isEqualTo(FieldKind.Email)
        assertThat(p.suggestionsEnabled).isFalse()
    }

    @Test
    fun numberClassBitsAreNotReadAsTextFlags() {
        // NUMBER_FLAG_SIGNED shares its bit with TEXT_FLAG_CAP_CHARACTERS.
        val p = profile(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
        assertThat(p.autoCapitalize).isEqualTo(AutoCapitalize.None)
        assertThat(p.multiLine).isFalse()
    }

    @Test
    fun filterVariationDisablesAutocorrectOnly() {
        val p = profile(text or InputType.TYPE_TEXT_VARIATION_FILTER)
        assertThat(p.autocorrectEnabled).isFalse()
        assertThat(p.suggestionsEnabled).isTrue()
        assertThat(p.learningEnabled).isTrue()
    }

    @Test
    fun noPersonalizedLearningFlagDisablesLearningOnly() {
        val p = profile(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertThat(p.learningEnabled).isFalse()
        assertThat(p.suggestionsEnabled).isTrue()
        assertThat(p.autocorrectEnabled).isTrue()
    }

    @Test
    fun autoCapitalizeFlags() {
        val cases =
            mapOf(
                0 to AutoCapitalize.None,
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS to AutoCapitalize.Characters,
                InputType.TYPE_TEXT_FLAG_CAP_WORDS to AutoCapitalize.Words,
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES to AutoCapitalize.Sentences,
            )
        for ((flag, expected) in cases) {
            assertThat(profile(text or flag).autoCapitalize).isEqualTo(expected)
        }
    }

    @Test
    fun characterCapWinsOverWordsAndSentences() {
        val flags =
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        assertThat(profile(text or flags).autoCapitalize).isEqualTo(AutoCapitalize.Characters)
        assertThat(
                profile(
                        text or
                            InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    )
                    .autoCapitalize
            )
            .isEqualTo(AutoCapitalize.Words)
    }

    @Test
    fun personNameDefaultsToWordsButRespectsExplicitFlag() {
        val name = text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        assertThat(profile(name).autoCapitalize).isEqualTo(AutoCapitalize.Words)
        assertThat(profile(name or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS).autoCapitalize)
            .isEqualTo(AutoCapitalize.Characters)
    }

    @Test
    fun multiLineFlags() {
        assertThat(profile(text).multiLine).isFalse()
        assertThat(profile(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE).multiLine).isTrue()
        assertThat(profile(text or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE).multiLine).isTrue()
    }

    @Test
    fun numberFlags() {
        val plain = profile(InputType.TYPE_CLASS_NUMBER)
        assertThat(plain.signedNumber).isFalse()
        assertThat(plain.decimalNumber).isFalse()

        val signed = profile(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
        assertThat(signed.signedNumber).isTrue()
        assertThat(signed.decimalNumber).isFalse()

        val decimal = profile(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        assertThat(decimal.signedNumber).isFalse()
        assertThat(decimal.decimalNumber).isTrue()

        val both =
            profile(
                InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_SIGNED or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL
            )
        assertThat(both.signedNumber).isTrue()
        assertThat(both.decimalNumber).isTrue()
    }

    @Test
    fun textClassNeverReportsNumberFlags() {
        val p =
            profile(text or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        assertThat(p.signedNumber).isFalse()
        assertThat(p.decimalNumber).isFalse()
    }

    @Test
    fun enterActionComesFromImeOptions() {
        assertThat(profile(text, EditorInfo.IME_ACTION_SEARCH).enterAction)
            .isEqualTo(EnterAction.Editor(EditorInfo.IME_ACTION_SEARCH))
        assertThat(
                profile(text, EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION)
                    .enterAction
            )
            .isEqualTo(EnterAction.NewLine)
        assertThat(profile(InputType.TYPE_CLASS_PHONE, EditorInfo.IME_ACTION_DONE).enterAction)
            .isEqualTo(EnterAction.Editor(EditorInfo.IME_ACTION_DONE))
    }

    @Test
    fun editorInfoOverloadReadsInputTypeAndImeOptions() {
        val info =
            EditorInfo().apply {
                inputType = text or InputType.TYPE_TEXT_VARIATION_URI
                imeOptions = EditorInfo.IME_ACTION_GO
            }
        val p = FieldProfile.from(info)
        assertThat(p.kind).isEqualTo(FieldKind.Url)
        assertThat(p.enterAction).isEqualTo(EnterAction.Editor(EditorInfo.IME_ACTION_GO))
    }
}

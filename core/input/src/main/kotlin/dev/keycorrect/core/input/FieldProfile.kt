package dev.keycorrect.core.input

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** Semantic kind of the focused field, driving layout and the strip content. */
enum class FieldKind {
    Text,
    Email,
    Url,
    Password,
    VisiblePassword,
    Number,
    Phone,
    DateTime,
    PersonName,
    PostalAddress,
    Terminal,
}

/** Automatic capitalization requested by the field. */
enum class AutoCapitalize {
    None,
    Characters,
    Words,
    Sentences,
}

/**
 * How the keyboard should adapt to the focused field (PLAN §8).
 *
 * @property sensitive true for any password variation; nothing typed may be suggested, learned or
 *   sent to an engine.
 */
data class FieldProfile(
    val kind: FieldKind,
    val enterAction: EnterAction,
    val autoCapitalize: AutoCapitalize,
    val multiLine: Boolean,
    val suggestionsEnabled: Boolean,
    val autocorrectEnabled: Boolean,
    val learningEnabled: Boolean,
    val sensitive: Boolean,
    val signedNumber: Boolean,
    val decimalNumber: Boolean,
) {
    companion object {
        /** Derives a profile from the [EditorInfo] of the focused field. */
        fun from(info: EditorInfo): FieldProfile = from(info.inputType, info.imeOptions)

        /** Derives a profile from raw [inputType] and [imeOptions] bits. */
        fun from(inputType: Int, imeOptions: Int): FieldProfile {
            val inputClass = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val noPersonalizedLearning =
                imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
            val enter = EnterAction.from(imeOptions)

            if (inputType == InputType.TYPE_NULL) {
                return FieldProfile(
                    kind = FieldKind.Terminal,
                    enterAction = EnterAction.NewLine,
                    autoCapitalize = AutoCapitalize.None,
                    multiLine = false,
                    suggestionsEnabled = false,
                    autocorrectEnabled = false,
                    learningEnabled = false,
                    sensitive = false,
                    signedNumber = false,
                    decimalNumber = false,
                )
            }

            val isText = inputClass == InputType.TYPE_CLASS_TEXT
            val kind =
                when (inputClass) {
                    InputType.TYPE_CLASS_NUMBER -> FieldKind.Number
                    InputType.TYPE_CLASS_PHONE -> FieldKind.Phone
                    InputType.TYPE_CLASS_DATETIME -> FieldKind.DateTime
                    InputType.TYPE_CLASS_TEXT -> textKind(variation)
                    else -> FieldKind.Text
                }
            val sensitive =
                when (kind) {
                    FieldKind.Password,
                    FieldKind.VisiblePassword -> true
                    FieldKind.Number -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    else -> false
                }

            val noSuggestions = isText && inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
            val suggestions =
                !sensitive &&
                    !noSuggestions &&
                    when (kind) {
                        FieldKind.Number,
                        FieldKind.Phone,
                        FieldKind.DateTime -> false
                        else -> true
                    }
            val autocorrect =
                !sensitive &&
                    !noSuggestions &&
                    when (kind) {
                        FieldKind.Email,
                        FieldKind.Url,
                        FieldKind.Number,
                        FieldKind.Phone,
                        FieldKind.DateTime -> false
                        else -> !(isText && variation == InputType.TYPE_TEXT_VARIATION_FILTER)
                    }
            val learning =
                !sensitive &&
                    !noPersonalizedLearning &&
                    when (kind) {
                        FieldKind.Number,
                        FieldKind.Phone,
                        FieldKind.DateTime -> false
                        else -> true
                    }

            var autoCapitalize = AutoCapitalize.None
            if (isText) {
                autoCapitalize =
                    when {
                        inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0 ->
                            AutoCapitalize.Characters
                        inputType and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0 ->
                            AutoCapitalize.Words
                        inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0 ->
                            AutoCapitalize.Sentences
                        kind == FieldKind.PersonName -> AutoCapitalize.Words
                        else -> AutoCapitalize.None
                    }
            }

            val multiLine =
                isText &&
                    inputType and
                        (InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0

            val isNumber = inputClass == InputType.TYPE_CLASS_NUMBER
            return FieldProfile(
                kind = kind,
                enterAction = enter,
                autoCapitalize = autoCapitalize,
                multiLine = multiLine,
                suggestionsEnabled = suggestions,
                autocorrectEnabled = autocorrect,
                learningEnabled = learning,
                sensitive = sensitive,
                signedNumber = isNumber && inputType and InputType.TYPE_NUMBER_FLAG_SIGNED != 0,
                decimalNumber = isNumber && inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0,
            )
        }

        private fun textKind(variation: Int): FieldKind =
            when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> FieldKind.Password
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> FieldKind.VisiblePassword
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.Email
                InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.Url
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> FieldKind.PersonName
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> FieldKind.PostalAddress
                else -> FieldKind.Text
            }
    }
}

package dev.keycorrect.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ValidationTest {
    @Test
    fun wordsAreTrimmedButKeepCase() {
        assertThat(Validation.normalizeWord("  McDonald ")).isEqualTo("McDonald")
    }

    @Test
    fun validWordsPass() {
        assertThat(Validation.checkWord("don't")).isNull()
        assertThat(Validation.checkWord("naïve", 255)).isNull()
        assertThat(Validation.checkWord("x", 1)).isNull()
    }

    @Test
    fun invalidWordsAreRejected() {
        assertThat(Validation.checkWord("   ")).isEqualTo(ValidationError.EMPTY_WORD)
        assertThat(Validation.checkWord("two words"))
            .isEqualTo(ValidationError.WORD_CONTAINS_WHITESPACE)
        assertThat(Validation.checkWord("a".repeat(65))).isEqualTo(ValidationError.WORD_TOO_LONG)
        assertThat(Validation.checkWord("ok", 0)).isEqualTo(ValidationError.FREQUENCY_OUT_OF_RANGE)
        assertThat(Validation.checkWord("ok", 256))
            .isEqualTo(ValidationError.FREQUENCY_OUT_OF_RANGE)
    }

    @Test
    fun blankLocaleBecomesNull() {
        assertThat(Validation.normalizeLocale("  ")).isNull()
        assertThat(Validation.normalizeLocale(null)).isNull()
        assertThat(Validation.normalizeLocale(" en-GB ")).isEqualTo("en-GB")
    }

    @Test
    fun triggersAreTrimmedAndLowercased() {
        assertThat(Validation.normalizeTrigger("  OMW ")).isEqualTo("omw")
        // Locale.ROOT: Turkish dotless-i rules must not apply.
        assertThat(Validation.normalizeTrigger("TITLE")).isEqualTo("title")
    }

    @Test
    fun invalidShortcutsAreRejected() {
        assertThat(Validation.checkShortcut("  ", "x")).isEqualTo(ValidationError.EMPTY_TRIGGER)
        assertThat(Validation.checkShortcut("a b", "x"))
            .isEqualTo(ValidationError.TRIGGER_CONTAINS_WHITESPACE)
        assertThat(Validation.checkShortcut("a\tb", "x"))
            .isEqualTo(ValidationError.TRIGGER_CONTAINS_WHITESPACE)
        assertThat(Validation.checkShortcut("a".repeat(65), "x"))
            .isEqualTo(ValidationError.TRIGGER_TOO_LONG)
        assertThat(Validation.checkShortcut("omw", "")).isEqualTo(ValidationError.EMPTY_EXPANSION)
        assertThat(Validation.checkShortcut("omw", "x".repeat(1001)))
            .isEqualTo(ValidationError.EXPANSION_TOO_LONG)
    }

    @Test
    fun expansionLimitIsInclusiveAndVariablesAreVerbatim() {
        assertThat(Validation.checkShortcut("omw", "x".repeat(1000))).isNull()
        assertThat(Validation.checkShortcut("sig", "Best,\n{cursor} {date} {time} {clipboard}"))
            .isNull()
    }
}

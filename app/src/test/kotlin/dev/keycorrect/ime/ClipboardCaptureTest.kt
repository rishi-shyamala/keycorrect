package dev.keycorrect.ime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClipboardCaptureTest {
    private val stripTags: (String) -> String = { it.replace(Regex("<[^>]*>"), "") }

    @Test
    fun plainTextWinsOverHtml() {
        assertThat(clipTextOf("plain", "<b>html</b>", stripTags)).isEqualTo("plain")
    }

    @Test
    fun htmlOnlyIsTakenAsPlainText() {
        assertThat(clipTextOf(null, "<b>html</b>", stripTags)).isEqualTo("html")
    }

    @Test
    fun anItemWithOnlyAUriOrIntentCarriesNoText() {
        // Such an item is never "coerced" (that would read the file the URI points to).
        assertThat(clipTextOf(null, null, stripTags)).isNull()
    }
}

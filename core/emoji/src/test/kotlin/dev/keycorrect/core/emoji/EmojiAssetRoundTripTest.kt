package dev.keycorrect.core.emoji

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class EmojiAssetRoundTripTest {
    private fun bytes(c: EmojiCatalog) =
        ByteArrayOutputStream().also { EmojiCatalogWriter.write(c, it) }.toByteArray()

    private fun read(text: String) = EmojiCatalog.read(ByteArrayInputStream(text.toByteArray()))

    @Test
    fun roundTripPreservesEverything() {
        val back = EmojiCatalog.read(ByteArrayInputStream(bytes(TestCatalog.catalog)))
        assertThat(back.unicodeVersion).isEqualTo("17.0")
        assertThat(back.cldrVersion).isEqualTo("48.2")
        assertThat(back.groups).isEqualTo(TestCatalog.catalog.groups)
        assertThat(back.kaomojiGroups).isEqualTo(TestCatalog.catalog.kaomojiGroups)
        assertThat(back.kaomoji).isEqualTo(TestCatalog.catalog.kaomoji)
    }

    @Test
    fun outputIsDeterministicTextWithHeader() {
        val text = String(bytes(TestCatalog.catalog))
        assertThat(text).startsWith("KCE1\t1\t17.0\t48.2\nG\tTest\nE\t")
        assertThat(bytes(TestCatalog.catalog)).isEqualTo(bytes(TestCatalog.catalog))
    }

    @Test
    fun emptyKeywordsAndVariantsSurvive() {
        val e = TestCatalog.emoji("X", "bare", group = "G")
        val c = EmojiCatalog("1.0", "2.0", listOf(EmojiGroup("G", listOf(e))), emptyList())
        assertThat(EmojiCatalog.read(ByteArrayInputStream(bytes(c))).groups).isEqualTo(c.groups)
    }

    @Test
    fun rejectsMalformedInput() {
        val good = String(bytes(TestCatalog.catalog))
        assertThrows(EmojiFormatException::class.java) { read("") }
        assertThrows(EmojiFormatException::class.java) { read(good.replace("KCE1", "KCE9")) }
        assertThrows(EmojiFormatException::class.java) { read(good.replace("KCE1\t1", "KCE1\t2")) }
        // Truncated: no trailer.
        assertThrows(EmojiFormatException::class.java) { read(good.substringBeforeLast("Z\t")) }
        // Counts that disagree with the data.
        assertThrows(EmojiFormatException::class.java) {
            read(good.replace(Regex("Z\t\\d+"), "Z\t999"))
        }
        // Variant before any emoji; unknown record.
        assertThrows(EmojiFormatException::class.java) { read("KCE1\t1\t1\t1\nV\ta\tb\n") }
        assertThrows(EmojiFormatException::class.java) { read("KCE1\t1\t1\t1\nQ\ta\n") }
    }

    @Test
    fun writerRejectsSeparatorsInFields() {
        val bad = TestCatalog.emoji("X", "bad\tname")
        val c = EmojiCatalog("1", "1", listOf(EmojiGroup("G", listOf(bad))), emptyList())
        assertThrows(IllegalArgumentException::class.java) { bytes(c) }
    }
}

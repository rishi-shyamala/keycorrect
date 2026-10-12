package dev.keycorrect.core.emoji

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The committed picker asset must load, be complete, and be fast to parse. */
class ShippedAssetTest {
    private val file = File("../../app/src/main/assets/emoji/emoji_en.kce")

    @Test(timeout = 60_000)
    fun shippedAssetLoadsWithExpectedContent() {
        assumeTrue("asset not present", file.isFile)
        val bytes = file.readBytes()
        val start = System.nanoTime()
        val c = EmojiCatalog.read(bytes.inputStream())
        val ms = (System.nanoTime() - start) / 1e6
        println("emoji_en.kce: ${bytes.size} bytes, parsed in %.1f ms (cold JVM)".format(ms))
        assertThat(ms).isLessThan(1_000.0)

        assertThat(c.unicodeVersion).isEqualTo("17.0")
        assertThat(c.cldrVersion).isEqualTo("48.2")
        assertThat(c.groups.map { it.name })
            .containsExactly(
                "Smileys & Emotion",
                "People & Body",
                "Animals & Nature",
                "Food & Drink",
                "Travel & Places",
                "Activities",
                "Objects",
                "Symbols",
                "Flags",
            )
            .inOrder()
        val all = c.groups.flatMap { it.emoji }
        println(
            "base emoji: ${all.size}, variants: ${all.sumOf { it.variants.size }}, kaomoji: ${c.kaomoji.size}"
        )
        assertThat(all.size).isAtLeast(1_700)
        assertThat(all.map { it.value }).containsNoDuplicates()
        assertThat(all.all { it.name.isNotEmpty() && it.keywords.isNotEmpty() }).isTrue()
        assertThat(c.kaomoji.size).isAtLeast(80)

        val thumbs = c.byValue("\uD83D\uDC4D")!!
        assertThat(thumbs.name).isEqualTo("thumbs up")
        assertThat(thumbs.variants).hasSize(5)
        assertThat(thumbs.variants.map { it.label }).contains("medium skin tone")
        // Person forms gather tones and genders; tone-bearing values are not grid entries.
        val running = c.byValue("\uD83C\uDFC3")!!
        assertThat(running.variants.map { it.label })
            .containsAtLeast("man", "woman", "man, light skin tone")
        assertThat(all.none { e -> e.value.codePoints().anyMatch { it in 0x1F3FB..0x1F3FF } })
            .isTrue()
    }

    @Test(timeout = 60_000)
    fun pizzaFindsTheSliceAndHappyIsNotStrong() {
        assumeTrue("asset not present", file.isFile)
        val c = EmojiCatalog.read(file.inputStream())
        val pizza = "\uD83C\uDF55"
        assertThat(c.search("pizza", 5).first().value).isEqualTo(pizza)
        assertThat(c.strongMatch("pizza")?.value).isEqualTo(pizza)
        assertThat(c.strongMatch("happy")).isNull()
        assertThat(c.strongMatch("ok")).isNull()
        assertThat(c.search("thumbs up", 3).first().value).isEqualTo("\uD83D\uDC4D")
    }

    @Test
    fun warmParseTime() {
        assumeTrue("asset not present", file.isFile)
        val bytes = file.readBytes()
        repeat(5) { EmojiCatalog.read(bytes.inputStream()) }
        val start = System.nanoTime()
        repeat(10) { EmojiCatalog.read(bytes.inputStream()) }
        val ms = (System.nanoTime() - start) / 1e6 / 10
        println("emoji_en.kce: warm parse %.1f ms".format(ms))
        assertThat(ms).isLessThan(250.0)
    }
}

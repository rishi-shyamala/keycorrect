package dev.keycorrect.tools.emojibuild

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.emoji.Emoji
import dev.keycorrect.core.emoji.EmojiCatalog
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogBuilderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val annotations =
        mapOf(
            CldrAnnotations.key("🍕") to Annotation("pizza", listOf("cheese", "food")),
            CldrAnnotations.key("❤️") to Annotation("red heart", listOf("love")),
        )

    private fun build(): EmojiCatalog =
        CatalogBuilder.build(
            EmojiTestParser.parse(EMOJI_TEST_FIXTURE, "f"),
            annotations,
            "48.2",
            emptyList(),
        )

    private fun EmojiCatalog.find(name: String): Emoji =
        groups.flatMap { it.emoji }.first { it.name == name }

    @Test
    fun groupsKeepOrderAndDropComponentAndNonFullyQualified() {
        val c = build()
        assertThat(c.groups.map { it.name })
            .containsExactly("Smileys & Emotion", "People & Body", "Food & Drink")
            .inOrder()
        assertThat(c.groups[0].emoji.map { it.value }).containsExactly("😀", "❤️").inOrder()
        assertThat(c.cldrVersion).isEqualTo("48.2")
        assertThat(c.unicodeVersion).isEqualTo("17.0")
    }

    @Test
    fun annotationsSupplyNameAndKeywordsWithFallback() {
        val c = build()
        assertThat(c.find("pizza").keywords).containsExactly("cheese", "food").inOrder()
        assertThat(c.find("grinning face").keywords).isEmpty() // no annotation: emoji-test name
    }

    @Test
    fun skinTonesAreVariantsOfTheirBase() {
        val thumbs = build().find("thumbs up")
        assertThat(thumbs.variants).hasSize(5)
        assertThat(thumbs.variants[2].label).isEqualTo("medium skin tone")
        assertThat(thumbs.variants[0].value).isEqualTo("👍🏻")
        val all = build().groups.flatMap { it.emoji }.map { it.name }
        assertThat(all.none { ": " in it }).isTrue()
    }

    @Test
    fun mixedToneHandshakeJoinsTheHandshakeByName() {
        val hs = build().find("handshake")
        assertThat(hs.variants.map { it.label })
            .containsExactly("light skin tone, medium-light skin tone")
    }

    @Test
    fun genderFormsAreVariantsOfThePersonForm() {
        val c = build()
        val running = c.find("person running")
        assertThat(running.variants.map { it.label })
            .containsExactly("light skin tone", "man", "man, light skin tone", "woman")
            .inOrder()
        val facing = c.find("person running facing right")
        assertThat(facing.variants.map { it.label }).containsExactly("man")
        val health = c.find("health worker")
        assertThat(health.variants.map { it.label }).containsExactly("man", "woman").inOrder()
        // "man"/"woman" themselves stay in the grid, and so do forms with no person form.
        assertThat(c.find("man").variants).isEmpty()
        assertThat(c.find("woman")).isNotNull()
        assertThat(c.find("men with bunny ears").variants).isEmpty()
    }

    @Test
    fun toneFormWithoutBaseIsAnError() {
        val text =
            "# Version: 17.0\n# group: A\n1F44D 1F3FB ; fully-qualified # 👍🏻 E1.0 odd: light skin tone\n"
        val e =
            assertThrows(InputException::class.java) {
                CatalogBuilder.build(
                    EmojiTestParser.parse(text, "t"),
                    emptyMap(),
                    "1.0",
                    emptyList(),
                )
            }
        assertThat(e).hasMessageThat().contains("no base emoji")
    }

    @Test
    fun parsesCldrAnnotationFilesFirstFileWinning() {
        val dtd = "<!DOCTYPE ldml SYSTEM \"../../common/dtd/ldml.dtd\">"
        fun xml(name: String, body: String): File =
            tmp.newFile(name).also {
                it.writeText(
                    "<?xml version=\"1.0\"?>\n$dtd\n<ldml><annotations>$body</annotations></ldml>"
                )
            }
        val base =
            xml(
                "a.xml",
                """<annotation cp="🍕">cheese | food | pizza</annotation>
                   <annotation cp="🍕" type="tts">pizza</annotation>
                   <annotation cp="❤">love | R&amp;B</annotation>""",
            )
        val derived = xml("d.xml", """<annotation cp="🍕" type="tts">other</annotation>""")
        val a = CldrAnnotations.parse(listOf(base, derived))
        assertThat(a[CldrAnnotations.key("🍕")])
            .isEqualTo(Annotation("pizza", listOf("cheese", "food", "pizza")))
        assertThat(a[CldrAnnotations.key("❤️")]!!.keywords).containsExactly("love", "R&B")
        val missing =
            assertThrows(InputException::class.java) {
                CldrAnnotations.parse(listOf(File("nope.xml")))
            }
        assertThat(missing).hasMessageThat().contains("not found")
        val garbage = tmp.newFile("g.xml").also { it.writeText("not xml <<") }
        assertThrows(InputException::class.java) { CldrAnnotations.parse(listOf(garbage)) }
    }

    @Test
    fun cliDerivesCldrVersionAndRejectsBadOptions() {
        val o = Options.parse(listOf("--out", "x.kce"))
        assertThat(o.cldrVersion).isEqualTo("48.2")
        assertThat(o.out.path).isEqualTo("x.kce")
        assertThrows(InputException::class.java) { Options.parse(listOf("--bogus", "1")) }
        assertThrows(InputException::class.java) {
            Options.parse(listOf("--annotations", "en.xml"))
        }
    }

    @Test
    fun kaomojiAreUniqueAndSeparatorSafe() {
        val all = Kaomoji.groups.flatMap { it.items }
        assertThat(all).containsNoDuplicates()
        assertThat(all.size).isIn(com.google.common.collect.Range.closed(80, 130))
        assertThat(all.none { s -> s.any { it == '\t' || it == '\n' } }).isTrue()
    }
}

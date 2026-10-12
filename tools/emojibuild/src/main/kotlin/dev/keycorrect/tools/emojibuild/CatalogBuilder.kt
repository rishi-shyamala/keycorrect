package dev.keycorrect.tools.emojibuild

import dev.keycorrect.core.emoji.Emoji
import dev.keycorrect.core.emoji.EmojiCatalog
import dev.keycorrect.core.emoji.EmojiGroup
import dev.keycorrect.core.emoji.EmojiVariant

/**
 * Turns emoji-test entries plus CLDR annotations into the picker catalog.
 *
 * Only fully-qualified emoji are kept and the "Component" group is dropped. A skin-tone form
 * becomes a variant of its tone-less emoji, and a gender form (man/woman running, man/woman health
 * worker, ...) becomes a variant of the matching "person" emoji when one exists. The tone forms of
 * a gender form are variants of the person emoji too, so a base emoji's variants are a flat list in
 * emoji-test order, e.g. 5 tones, "man", 5 "man, ... tone", "woman", ...
 */
object CatalogBuilder {
    private const val ZWJ = 0x200D
    private const val VS16 = 0xFE0F
    private const val MALE = 0x2642
    private const val FEMALE = 0x2640
    private const val MAN = 0x1F468
    private const val WOMAN = 0x1F469
    private const val PERSON = 0x1F9D1
    private const val FIRST_TONE = 0x1F3FB
    private const val LAST_TONE = 0x1F3FF
    private const val COMPONENT_GROUP = "Component"

    private val TONE_LABELS =
        listOf("light", "medium-light", "medium", "medium-dark", "dark").map { "$it skin tone" }

    fun build(
        test: EmojiTestFile,
        annotations: Map<String, Annotation>,
        cldrVersion: String,
        kaomoji: List<dev.keycorrect.core.emoji.KaomojiGroup>,
    ): EmojiCatalog {
        val fq =
            test.entries.filter { it.status == "fully-qualified" && it.group != COMPONENT_GROUP }
        val toneless = HashMap<List<Int>, TestEntry>()
        for (e in fq.filter { tonesOf(it).isEmpty() }) {
            check(toneless.put(keyOf(e), e) == null) { "duplicate emoji ${e.value}" }
        }
        val variants = HashMap<TestEntry, MutableList<EmojiVariant>>()
        val topLevel = ArrayList<TestEntry>()
        for (e in fq) {
            val tones = tonesOf(e)
            val plain = if (tones.isEmpty()) e else toneBase(e, toneless)
            val person = personForm(plain, toneless)
            when {
                person == null && tones.isEmpty() -> topLevel += e
                else -> {
                    val parts = listOfNotNull(person?.let { genderLabel(plain) }, toneLabel(tones))
                    variants.getOrPut(person ?: plain) { ArrayList() } +=
                        EmojiVariant(e.value, parts.joinToString(", "))
                }
            }
        }
        val groups =
            topLevel
                .groupByTo(LinkedHashMap()) { it.group }
                .map { (name, list) ->
                    EmojiGroup(name, list.map { toEmoji(it, annotations, variants[it].orEmpty()) })
                }
        return EmojiCatalog(test.unicodeVersion, cldrVersion, groups, kaomoji)
    }

    /**
     * The tone-less emoji a skin-tone form belongs to: the same sequence without tones, or, for
     * mixed-tone forms with no such sequence (handshake: 1FAF1 1FAF2), the emoji of that name.
     */
    private fun toneBase(e: TestEntry, toneless: Map<List<Int>, TestEntry>): TestEntry {
        val byName = e.name.substringBefore(": ")
        return toneless[keyOf(e)]
            ?: toneless.values.firstOrNull { it.name == byName }
            ?: invalid("skin-tone form ${e.value} (${e.name}) has no base emoji")
    }

    private fun toEmoji(
        e: TestEntry,
        annotations: Map<String, Annotation>,
        variants: List<EmojiVariant>,
    ): Emoji {
        val a = annotations[CldrAnnotations.key(e.value)]
        return Emoji(e.value, a?.name ?: e.name, a?.keywords.orEmpty(), variants, e.group)
    }

    private fun tonesOf(e: TestEntry): List<Int> =
        e.codepoints.filter { it in FIRST_TONE..LAST_TONE }

    /** Code points without skin tones and variation selectors: identifies an emoji across forms. */
    private fun keyOf(e: TestEntry): List<Int> =
        e.codepoints.filter { it !in FIRST_TONE..LAST_TONE && it != VS16 }

    private fun toneLabel(tones: List<Int>): String? =
        tones.map { TONE_LABELS[it - FIRST_TONE] }.distinct().joinToString(", ").ifEmpty { null }

    /** The gender-neutral "person" form of a tone-less gender form, or null if it is not one. */
    private fun personForm(e: TestEntry, toneless: Map<List<Int>, TestEntry>): TestEntry? {
        val key = keyOf(e)
        val sign =
            key.indices.firstOrNull {
                it > 0 && key[it - 1] == ZWJ && key[it] in setOf(MALE, FEMALE)
            }
        val neutral =
            when {
                sign != null -> key.filterIndexed { i, _ -> i != sign && i != sign - 1 }
                key.size > 1 && key[0] in setOf(MAN, WOMAN) -> listOf(PERSON) + key.drop(1)
                else -> return null
            }
        return toneless[neutral]
    }

    private fun genderLabel(e: TestEntry): String {
        val plural = e.name.startsWith("men ") || e.name.startsWith("women ")
        val female = FEMALE in e.codepoints || e.codepoints.firstOrNull() == WOMAN
        return when {
            plural -> if (female) "women" else "men"
            female -> "woman"
            else -> "man"
        }
    }
}

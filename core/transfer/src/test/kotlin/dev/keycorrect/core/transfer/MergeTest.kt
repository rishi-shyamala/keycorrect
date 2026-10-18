package dev.keycorrect.core.transfer

import com.google.common.truth.Truth.assertThat
import kotlin.math.pow
import org.junit.Test

/** No decay at all, so sums are plain sums. */
private val noAging = Merge.Aging(evidence = { 1.0 }, touch = { 1.0 })

/** Halves every [halfLife] ms, like the learning model's decay. */
private fun halving(halfLife: Long) = { elapsed: Long -> 0.5.pow(elapsed.toDouble() / halfLife) }

class MergeTest {
    @Test
    fun wordsUnionAddFrequencyCapAndKeepEarliest() {
        val r =
            Merge.words(
                listOf(DictWord("a", 200, null, 5, 5), DictWord("b", 10)),
                listOf(DictWord("a", 100, null, 2, 9), DictWord("c", 7)),
            )
        assertThat(r.map { it.word }).containsExactly("a", "b", "c").inOrder()
        assertThat(r[0]).isEqualTo(DictWord("a", 255, null, 2, 9))
    }

    @Test
    fun wordsMatchByWordAloneAndKeepThisDevicesLocale() {
        // user_words has the word as its primary key: one row per word, whatever the locale.
        val r =
            Merge.words(
                listOf(DictWord("café", 10, "en", 5, 5)),
                listOf(DictWord("café", 20, "fr", 2, 9), DictWord("zorp", 7, "fr")),
            )
        assertThat(r.map { it.word }).containsExactly("café", "zorp").inOrder()
        assertThat(r[0]).isEqualTo(DictWord("café", 30, "en", 2, 9))
        assertThat(r[1].locale).isEqualTo("fr")
    }

    @Test
    fun blockedWinsOverWordsFromEitherSide() {
        val r =
            Merge.dictionary(
                DictionarySection(
                    words = listOf(DictWord("Foo", 5)),
                    blockedWords = listOf(BlockedWord("bar", 4)),
                ),
                DictionarySection(
                    words = listOf(DictWord("bar", 5), DictWord("ok", 5)),
                    blockedWords = listOf(BlockedWord("FOO", 2)),
                ),
            )
        assertThat(r.words.map { it.word }).containsExactly("ok")
        assertThat(r.blockedWords.map { it.word.lowercase() }).containsExactly("bar", "foo")
    }

    @Test
    fun shortcutsKeepExistingUnlessReplace() {
        val mine = listOf(ShortcutEntry("omw", "mine"))
        val theirs = listOf(ShortcutEntry("OMW", "theirs"), ShortcutEntry("brb", "be right back"))
        assertThat(
                Merge.shortcuts(mine, theirs).associate { it.trigger.lowercase() to it.expansion }
            )
            .containsExactly("omw", "mine", "brb", "be right back")
        assertThat(
                Merge.shortcuts(mine, theirs, replace = true)
                    .first { it.trigger.equals("omw", true) }
                    .expansion
            )
            .isEqualTo("theirs")
    }

    @Test
    fun tombstonesStick() {
        val r =
            Merge.learnedWords(
                listOf(LearnedWord("x", "x", removed = false, createdAtMillis = 5)),
                listOf(LearnedWord("x", "x", removed = true, createdAtMillis = 3)),
            )
        assertThat(r.single().removed).isTrue()
        assertThat(r.single().createdAtMillis).isEqualTo(3)
    }

    @Test
    fun learningCountsAdd() {
        val a = sampleBundle().learning
        val b =
            a.copy(
                ngrams = listOf(NgramEntry(2, "of the", 1.5, 120), NgramEntry(1, "new", 1.0, 1)),
                rankerUpdates = 50,
                rankerWeights = listOf(9.0),
                sentences = listOf(a.sentences[0], SentenceEntry(2, "other", 60)),
            )
        val m = Merge.learning(a, b, noAging)
        assertThat(m.ngrams.first { it.key == "of the" })
            .isEqualTo(NgramEntry(2, "of the", 5.0, 120))
        assertThat(m.ngrams).hasSize(2)
        assertThat(m.corrections.single().score).isEqualTo(4.0)
        assertThat(m.sentences.map { it.text }).hasSize(2)
        assertThat(m.rankerWeights).containsExactly(9.0)
        assertThat(m.rankerUpdates).isEqualTo(50)
    }

    @Test
    fun emojiRecentsAreCappedAndOrdered() {
        val a = (1..60).map { EmojiRecent("e$it", "e$it", it.toLong(), 1) }
        val b = (50..80).map { EmojiRecent("e$it", "e$it", it.toLong() + 100, 2) }
        val r = Merge.emojiRecents(a, b)
        assertThat(r).hasSize(64)
        assertThat(r.first().emoji).isEqualTo("e80")
        assertThat(r.first { it.emoji == "e55" }.useCount).isEqualTo(3)
    }

    @Test
    fun emojiVariantKeepsMine() {
        val r =
            Merge.emojiVariants(
                listOf(EmojiVariant("👍", "👍🏽")),
                listOf(EmojiVariant("👍", "👍🏿"), EmojiVariant("👋", "👋🏻")),
            )
        assertThat(r.associate { it.base to it.variant })
            .containsExactly("👍", "👍🏽", "👋", "👋🏻")
    }

    @Test
    fun touchStatsAdd() {
        val a =
            TouchSection(
                listOf(
                    TouchBucket("k", 10, listOf(TouchKeyStats(97, 5, 4.0, 1.0, 1.0, 1.0, 1.0, 3)))
                )
            )
        val b =
            TouchSection(
                listOf(
                    TouchBucket(
                        "k",
                        5,
                        listOf(
                            TouchKeyStats(97, 2, 1.0, 1.0, 2.0, 3.0, 4.0, 9),
                            TouchKeyStats(98, 1, 1.0, 0.0, 0.0, 0.0, 0.0),
                        ),
                    ),
                    TouchBucket("other"),
                )
            )
        val m = Merge.touch(a, b, noAging)
        assertThat(m.buckets.map { it.touchKey }).containsExactly("k", "other")
        val k = m.buckets.first { it.touchKey == "k" }
        assertThat(k.words).isEqualTo(15)
        assertThat(k.keys.first { it.char == 97 })
            .isEqualTo(TouchKeyStats(97, 7, 5.0, 2.0, 3.0, 4.0, 5.0, 9))
        assertThat(k.keys).hasSize(2)
    }

    // ------------------------------------------------------------- decay before adding

    @Test
    fun olderIncomingNgramCountsForLess() {
        val aging = Merge.Aging(evidence = halving(100), touch = { 1.0 })
        // This device's entry is 100 ms newer: the incoming 4.0 has halved by then.
        val m =
            Merge.ngrams(
                listOf(NgramEntry(1, "a", 4.0, 200)),
                listOf(NgramEntry(1, "a", 4.0, 100)),
                aging,
            )
        assertThat(m.single().weight).isWithin(1e-9).of(6.0)
        assertThat(m.single().lastActiveMillis).isEqualTo(200)
        // And the other way round: the existing one is the older.
        val n =
            Merge.ngrams(
                listOf(NgramEntry(1, "a", 4.0, 100)),
                listOf(NgramEntry(1, "a", 4.0, 300)),
                aging,
            )
        assertThat(n.single().weight).isWithin(1e-9).of(4.0 + 4.0 * 0.25)
        assertThat(n.single().lastActiveMillis).isEqualTo(300)
    }

    @Test
    fun olderCorrectionScoresDecayToo() {
        val aging = Merge.Aging(evidence = halving(100), touch = { 1.0 })
        val m =
            Merge.corrections(
                listOf(CorrectionEntry("teh", "the", "", 2.0, 100)),
                listOf(CorrectionEntry("teh", "the", "", 2.0, 200)),
                aging,
            )
        assertThat(m.single().score).isWithin(1e-9).of(3.0)
        assertThat(m.single().lastActiveMillis).isEqualTo(200)
    }

    @Test
    fun touchSumsDecayButSampleCountsAdd() {
        val aging = Merge.Aging(evidence = { 1.0 }, touch = halving(100))
        val a =
            TouchSection(
                listOf(
                    TouchBucket("k", 1, listOf(TouchKeyStats(97, 5, 4.0, 8.0, 8.0, 8.0, 8.0, 100)))
                )
            )
        val b =
            TouchSection(
                listOf(
                    TouchBucket("k", 1, listOf(TouchKeyStats(97, 2, 4.0, 4.0, 4.0, 4.0, 4.0, 200)))
                )
            )
        val key = Merge.touch(a, b, aging).buckets.single().keys.single()
        assertThat(key.samples).isEqualTo(7)
        assertThat(key.weight).isWithin(1e-9).of(6.0)
        assertThat(key.sumX).isWithin(1e-9).of(8.0)
        assertThat(key.sumYY).isWithin(1e-9).of(8.0)
        assertThat(key.lastActiveMillis).isEqualTo(200)
    }
}

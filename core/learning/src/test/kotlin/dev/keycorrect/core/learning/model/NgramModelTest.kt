package dev.keycorrect.core.learning.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NgramModelTest {
    @Test
    fun unseenOrWeakWordsHaveNoUserProbability() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, *Array(10) { used("common") }, used("rare"))
        val s = m.snapshot()
        assertThat(s.userLogProb(emptyList(), "common")).isNotNull()
        assertThat(s.userLogProb(emptyList(), "rare")).isNull() // weight 1 < 2
        assertThat(s.userLogProb(emptyList(), "never")).isNull()
    }

    @Test
    fun wordsSeenAfterAContextAreListedOnceTheyHaveEvidence() {
        val m = PersonalModel(testConfig())
        m.applyAt(
            0,
            *Array(10) { used("booked", "finally") },
            used("rare", "finally"),
            *Array(10) { used("filler") },
        )
        val s = m.snapshot()
        assertThat(s.userNextWords(listOf("finally"))).containsExactly("booked")
        assertThat(s.userNextWords(listOf("unknown"))).isEmpty()
        assertThat(s.userNextWords(emptyList())).isEmpty()
        assertThat(PersonalModel().snapshot().userNextWords(listOf("finally"))).isEmpty()
    }

    @Test
    fun theNextWordListSurvivesExportAndImport() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, *Array(30) { used("booked", "finally") })
        val copy = PersonalModel(testConfig())
        copy.import(m.export(), emptyList(), 0L)
        assertThat(copy.snapshot().userNextWords(listOf("finally"))).containsExactly("booked")
    }

    @Test
    fun anImportedContextKeepsItsHeaviestFollowersNotAnyEight() {
        val m = PersonalModel(testConfig())
        // w00 is the least used and w11 the most; the lightest ones were used last.
        for (i in 11 downTo 0) {
            val word = "w" + i.toString().padStart(2, '0')
            m.applyAt(0, *Array(3 + i) { used(word, "finally") })
        }
        val copy = PersonalModel(testConfig())
        copy.import(m.export(), emptyList(), 0L)
        val next = copy.snapshot().userNextWords(listOf("finally"))
        assertThat(next)
            .containsExactly("w11", "w10", "w09", "w08", "w07", "w06", "w05", "w04")
            .inOrder()
    }

    @Test
    fun nothingLearnedYieldsNull() {
        assertThat(PersonalModel().snapshot().userLogProb(listOf("a"), "b")).isNull()
        assertThat(PersonalModel().personalization.userLogProb(emptyList(), "b")).isNull()
    }

    @Test
    fun bigramContextRaisesProbabilityOfSeenContinuation() {
        val m = PersonalModel(testConfig())
        m.applyAt(
            0,
            *Array(10) { used("york", "new") },
            *Array(3) { used("car", "new") },
            *Array(10) { used("boat", "old") },
        )
        val s = m.snapshot()
        val yorkNew = s.userLogProb(listOf("new"), "york")!!
        val carNew = s.userLogProb(listOf("new"), "car")!!
        val yorkNoCtx = s.userLogProb(emptyList(), "york")!!
        assertThat(yorkNew).isGreaterThan(carNew)
        assertThat(yorkNew).isGreaterThan(yorkNoCtx)
        assertThat(yorkNew).isAtMost(0f)
    }

    @Test
    fun trigramContextIsUsed() {
        val m = PersonalModel(testConfig())
        m.applyAt(
            0,
            *Array(8) { used("night", "good", "very") },
            *Array(8) { used("day", "good", "very") },
            *Array(8) { used("day", "good", "a") },
        )
        val s = m.snapshot()
        val triNight = s.userLogProb(listOf("good", "very"), "night")!!
        val bigramOnlyNight = s.userLogProb(listOf("very"), "night")!!
        assertThat(triNight).isGreaterThan(bigramOnlyNight)
    }

    @Test
    fun keysAreCaseInsensitiveAndApostropheFolded() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, *Array(6) { used("Don’t", "I") })
        val s = m.snapshot()
        assertThat(s.userLogProb(listOf("i"), "don't")).isNotNull()
        assertThat(s.userLogProb(listOf("I"), "DON'T"))
            .isEqualTo(s.userLogProb(listOf("i"), "don't"))
    }

    @Test
    fun evidenceDecaysWithActiveTime() {
        val cfg = testConfig(PersonalModelConfig(halfLifeMillis = DAY_MS, minTotalEvidence = 1.0))
        val m = PersonalModel(cfg)
        m.applyAt(0, used("alpha", w = 3f))
        assertThat(m.snapshot().userLogProb(emptyList(), "alpha")).isNotNull()
        // One half-life later weight 3 -> 1.5 < threshold 2.
        m.applyAt(DAY_MS, used("filler"))
        assertThat(m.snapshot().userLogProb(emptyList(), "alpha")).isNull()
    }

    @Test
    fun longIdleGapIsCappedOnTheActiveClock() {
        val cfg =
            testConfig(
                PersonalModelConfig(
                    halfLifeMillis = DAY_MS,
                    maxActiveGapMillis = DAY_MS,
                    minTotalEvidence = 1.0,
                )
            )
        val m = PersonalModel(cfg)
        m.applyAt(0, used("alpha", w = 4f))
        // 100 wall days of silence count as one active day: 4 -> 2 (>= threshold), not ~0.
        m.applyAt(100 * DAY_MS, used("filler"))
        assertThat(m.snapshot().userLogProb(emptyList(), "alpha")).isNotNull()
        // Another 100 days: 2 -> 1.0 and now below the threshold.
        m.applyAt(200 * DAY_MS, used("filler"))
        assertThat(m.snapshot().userLogProb(emptyList(), "alpha")).isNull()
    }

    @Test
    fun sizeCapsEvictLowestDecayedWeight() {
        val cfg =
            testConfig(
                PersonalModelConfig(
                    maxUnigrams = 20,
                    maxBigrams = 15,
                    maxTrigrams = 10,
                    minTotalEvidence = 1.0,
                )
            )
        val m = PersonalModel(cfg)
        m.applyAt(0, used("heavy", "a", "b", w = 4f))
        for (i in 0 until 100) m.applyAt(1_000L + i, used("w$i", "c$i", "d$i"))
        val rows = m.export().ngrams
        assertThat(rows.count { it.order == 1 }).isAtMost(20)
        assertThat(rows.count { it.order == 2 }).isAtMost(15)
        assertThat(rows.count { it.order == 3 }).isAtMost(10)
        // The heaviest entries survive eviction.
        assertThat(rows.any { it.order == 1 && it.key == "heavy" }).isTrue()
        assertThat(rows.any { it.order == 3 && it.key == "a b heavy" }).isTrue()
    }

    @Test
    fun blockedWordsAreNotLearnedIntoTheCache() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, LearningUpdate_block("bad"))
        m.applyAt(1, *Array(10) { used("bad") })
        assertThat(m.snapshot().userLogProb(emptyList(), "bad")).isNull()
    }

    private fun LearningUpdate_block(w: String) =
        dev.keycorrect.core.learning.LearningUpdate.BlockWord(w)
}

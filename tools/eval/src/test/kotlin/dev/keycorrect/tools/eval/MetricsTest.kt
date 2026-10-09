package dev.keycorrect.tools.eval

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import org.junit.Test

class MetricsTest {
    private val keyboard = EvalKeyboard()

    /** An exact typist: every tap lands in the key centre, no cognitive typos. */
    private val exact =
        Typist(
            keyboard,
            TypistConfig(
                sigmaX = 0f,
                sigmaY = 0f,
                omissionRate = 0f,
                insertionRate = 0f,
                transpositionRate = 0f,
            ),
        )

    private fun cand(text: String, phrase: Boolean = false) =
        Candidate(text, 0f, CandidateSource.Dictionary, phrase)

    private fun parse(s: String) = Corpus.parse(s, null, NameMode.Skip)

    private fun run(
        sentence: String,
        options: EpisodeOptions = EpisodeOptions(),
        typist: Typist = exact,
        predictor: Predictor,
    ): Metrics {
        val m = Metrics()
        EpisodeRunner(predictor, keyboard.layout, typist, options, seed = 1)
            .run(0, parse(sentence), m)
        return m
    }

    /** Stub: never corrects, offers nothing. */
    private val passive = Predictor { r ->
        PredictResult(emptyList(), cand(r.composing.text), null)
    }

    @Test
    fun noCorrectionBaselineEqualsRawAccuracy() {
        val m = run("The cat sat.", predictor = passive)
        assertThat(m.all.words).isEqualTo(3)
        assertThat(m.all.rawAccuracy).isEqualTo(1.0)
        assertThat(m.all.wordAccuracy).isEqualTo(1.0)
        assertThat(m.all.falseCorrections).isEqualTo(0)
        assertThat(m.all.literalWrong).isEqualTo(0)
    }

    @Test
    fun falseCorrectionIsCountedWhenARightWordIsChanged() {
        // Autocorrects "cat" to "cut" and leaves the rest alone.
        val p = Predictor { r ->
            val fix = if (r.composing.text == "cat") cand("cut") else null
            PredictResult(emptyList(), cand(r.composing.text), fix)
        }
        val m = run("The cat sat.", predictor = p)
        assertThat(m.all.falseCorrections).isEqualTo(1)
        assertThat(m.all.falseCorrectionsPer1000).isWithin(1e-9).of(1000.0 / 3)
        assertThat(m.all.wordAccuracy).isWithin(1e-9).of(2.0 / 3)
        assertThat(m.all.autocorrectFired).isEqualTo(1)
        assertThat(m.all.firedWrong).isEqualTo(1)
        assertThat(m.failureCategories.keys).contains("false correction: real word changed")
    }

    @Test
    fun correctionsAndMissesAreCounted() {
        // The typist is sloppy-but-deterministic here: a stub that fixes "qat" -> "cat" only.
        val sloppy = Predictor { r ->
            val text = r.composing.text
            val fix = if (text == "cqt") cand("cat") else null
            PredictResult(listOf(cand("cat")), cand(text), fix)
        }
        // Build the input by hand through a typist that always mistypes the middle letter.
        val s = parse("cat dog")
        val m = Metrics()
        val wrongTypist =
            object {
                fun outcome(target: String, literal: String, committed: String, top: List<String>) =
                    WordOutcome(
                        target,
                        literal,
                        committed,
                        top,
                        committed != literal,
                        TypoKind.None,
                        0,
                        true,
                        literal.length,
                        null,
                        null,
                    )
            }
        m.record(wrongTypist.outcome("cat", "cqt", "cat", listOf("cat")))
        m.record(wrongTypist.outcome("dog", "dig", "dig", listOf("dog")))
        assertThat(s.tokens).hasSize(2)
        assertThat(
                sloppy
                    .predict(
                        PredictRequest(
                            dev.keycorrect.core.engineapi.TypingContext(),
                            dev.keycorrect.core.engineapi.ComposingInput(
                                "cqt",
                                listOf(null, null, null),
                            ),
                            keyboard.layout,
                        )
                    )
                    .autocorrect
                    ?.text
            )
            .isEqualTo("cat")
        assertThat(m.all.literalWrong).isEqualTo(2)
        assertThat(m.all.corrected).isEqualTo(1)
        assertThat(m.all.correctionRate).isEqualTo(0.5)
        assertThat(m.all.missedCorrections).isEqualTo(1)
        assertThat(m.all.missedTargetInTop3).isEqualTo(1)
        assertThat(m.all.top3Recall).isEqualTo(1.0)
        assertThat(m.failureCategories).containsKey("missed: target in top 3 but not applied")
    }

    @Test
    fun casingAccuracySeparatesFromWordAccuracy() {
        val o =
            WordOutcome(
                "Paris",
                "paris",
                "paris",
                emptyList(),
                false,
                TypoKind.None,
                0,
                true,
                5,
                null,
                null,
            )
        val s = Stats().also { it.add(o) }
        assertThat(s.wordAccuracy).isEqualTo(1.0)
        assertThat(s.casingAccuracy).isEqualTo(0.0)
    }

    @Test
    fun lengthBucketsPartitionWords() {
        assertThat(listOf(1, 2, 4, 5, 6, 7, 8, 9, 20).map { Metrics.lengthBucket(it) })
            .containsExactly("1", "2", "4", "5-6", "5-6", "7-8", "7-8", "9+", "9+")
            .inOrder()
        val m = run("a big dog jumped over", predictor = passive)
        assertThat(m.byLength.values.sumOf { it.words }).isEqualTo(m.all.words)
    }

    @Test
    fun completionTapSavesKeystrokesAndCountsTheTap() {
        // Offers "keyboard" once two letters are typed.
        val p = Predictor { r ->
            val t = r.composing.text
            val cands =
                if (t.length >= 2 && "keyboard".startsWith(t)) listOf(cand("keyboard"))
                else emptyList()
            PredictResult(cands, if (t.isEmpty()) null else cand(t), null)
        }
        val m = run("keyboard", predictor = p)
        // Typed 8 chars; tap after 2 chars = 3 keystrokes (2 letters + the tap).
        assertThat(m.all.completionBaseKeys).isEqualTo(8)
        assertThat(m.all.keysCompletion).isEqualTo(3)
        assertThat(m.all.ksrCompletion).isWithin(1e-9).of(1.0 - 3.0 / 8)
    }

    @Test
    fun nextWordTapCanReplaceTypingEntirely() {
        val p = Predictor { r ->
            if (r.composing.isEmpty) PredictResult(listOf(cand("hello")), null, null)
            else PredictResult(emptyList(), cand(r.composing.text), null)
        }
        val m = run("hello", predictor = p)
        assertThat(m.nextWord.top1).isEqualTo(1)
        assertThat(m.all.keysNextWord).isEqualTo(1)
        assertThat(m.all.ksrWithNextWord).isWithin(1e-9).of(1.0 - 1.0 / 5)
        assertThat(m.all.ksrCompletion).isEqualTo(0.0)
    }

    @Test
    fun nextWordTopOneAndTopThree() {
        val p = Predictor { r ->
            if (r.composing.isEmpty)
                PredictResult(listOf(cand("a"), cand("the"), cand("big")), null, null)
            else PredictResult(emptyList(), cand(r.composing.text), null)
        }
        val m = run("the big dog", predictor = p)
        assertThat(m.nextWord.predictions).isEqualTo(3)
        assertThat(m.nextWord.top1).isEqualTo(0)
        assertThat(m.nextWord.top3).isEqualTo(2)
        assertThat(m.nextWord.midPredictions).isEqualTo(2)
    }

    @Test
    fun phrasePrecisionComparesWithTheTrueContinuation() {
        val n = NextWordStats()
        val truth = listOf("see", "you", "tomorrow", "at", "noon")
        n.addPhrase("see you tomorrow", truth) // full match, 3 words
        n.addPhrase("see you soon", truth) // 2 of 3
        n.addPhrase("let me know", truth) // none
        n.addPhrase(null, truth) // opportunity, nothing offered
        assertThat(n.phraseOpportunities).isEqualTo(4)
        assertThat(n.phrasesOffered).isEqualTo(3)
        assertThat(n.phraseOfferRate).isWithin(1e-9).of(0.75)
        assertThat(n.phrasesFullyMatched).isEqualTo(1)
        assertThat(n.phraseFullPrecision).isWithin(1e-9).of(1.0 / 3)
        assertThat(n.phraseFirstWordPrecision).isWithin(1e-9).of(2.0 / 3)
        assertThat(n.phraseWordPrecision).isWithin(1e-9).of(5.0 / 9)
    }

    @Test
    fun committedContextPropagatesErrorsButOracleContextDoesNot() {
        val seen = ArrayList<String>()
        val p = Predictor { r ->
            if (!r.composing.isEmpty) seen.add(r.context.before)
            // Replace "one" by "won" every time.
            val fix = if (r.composing.text == "one") cand("won") else null
            PredictResult(
                emptyList(),
                if (r.composing.isEmpty) null else cand(r.composing.text),
                fix,
            )
        }
        val off = EpisodeOptions(completion = false, nextWord = false)
        run("one two", off, predictor = p)
        assertThat(seen.last()).isEqualTo("won ")
        seen.clear()
        run("one two", off.copy(oracleContext = true), predictor = p)
        assertThat(seen.last()).isEqualTo("one ")
    }

    @Test
    fun placeholderNamesAreSkippedOrSubstituted() {
        val skipped = Corpus.parse("Tom saw Mary's dog.", null, NameMode.Skip)
        assertThat(skipped.tokens.map { it.evaluated })
            .containsExactly(false, true, false, true)
            .inOrder()
        val sub = Corpus.parse("Tom saw Mary's dog.", null, NameMode.Substitute)
        assertThat(sub.tokens.map { it.text })
            .containsExactly("John", "saw", "Anna's", "dog")
            .inOrder()
        val m = run("Tom saw 3 dogs", predictor = passive)
        assertThat(m.all.words).isEqualTo(2)
        assertThat(m.skippedTokens).isEqualTo(2)
    }

    @Test
    fun noisyEpisodeIsDeterministic() {
        val noisy = Typist(keyboard, TypistConfig())
        val a =
            run("The quick brown fox jumps over the lazy dog.", typist = noisy, predictor = passive)
        val b =
            run("The quick brown fox jumps over the lazy dog.", typist = noisy, predictor = passive)
        assertThat(Report.statsMap(a.all)).isEqualTo(Report.statsMap(b.all))
        assertThat(a.all.rawAccuracy).isLessThan(1.0)
    }

    @Test
    fun devTestSplitIsStableAndDisjoint() {
        val dev = (0 until 10_000).count { Corpus.isDev(it) }
        assertThat(dev / 10_000.0).isWithin(0.02).of(Corpus.DEV_PERCENT / 100.0)
        assertThat(Split.Dev.contains(true)).isTrue()
        assertThat(Split.Test.contains(true)).isFalse()
        assertThat(Split.All.contains(false)).isTrue()
        assertThat(Corpus.isDev(12345)).isEqualTo(Corpus.isDev(12345))
    }
}

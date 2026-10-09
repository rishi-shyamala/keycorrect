package dev.keycorrect.core.decoder

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.ComposingInput
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.engineapi.TypingContext
import org.junit.Test

class ClassicEngineTest {
    private val dictionary = TestDictionary.dictionary

    private fun engine(
        config: ClassicConfig = ClassicConfig(),
        userWords: Collection<String> = emptyList(),
        shortcuts: Map<String, String> = emptyMap(),
    ) = ClassicEngine(dictionary, config, { userWords }, { shortcuts })

    private fun request(
        composing: ComposingInput,
        before: String = "",
        after: String = "",
        maxCandidates: Int = 3,
        maxPhraseWords: Int = 0,
        autocorrectAllowed: Boolean = true,
        field: FieldClass = FieldClass.Text,
    ) =
        PredictRequest(
            TypingContext(before, after, field),
            composing,
            TestKeyboard.layout,
            maxCandidates,
            maxPhraseWords,
            autocorrectAllowed,
        )

    private fun PredictResult.texts() = candidates.map { it.text }

    private val engine = engine()

    // ------------------------------------------------------------------ corrections

    @Test
    fun exactWordKeepsLiteralAndDoesNotAutocorrect() {
        val r = engine.predictNow(request(TestKeyboard.typed("hello")))
        assertThat(r.literal?.text).isEqualTo("hello")
        assertThat(r.literal?.source).isEqualTo(CandidateSource.Literal)
        assertThat(r.autocorrect).isNull()
        assertThat(r.texts()).doesNotContain("hello")
    }

    @Test
    fun neighbourKeyTypoIsAutocorrected() {
        val r = engine.predictNow(request(TestKeyboard.typed("helli")))
        assertThat(r.literal?.text).isEqualTo("helli")
        assertThat(r.autocorrect?.text).isEqualTo("hello")
        assertThat(r.texts().first()).isEqualTo("hello")
    }

    @Test
    fun slightlyOffTouchDoesNotChangeTheWord() {
        // A tap near the edge of 'o' still reads as hello, with no correction needed.
        val r =
            engine.predictNow(
                request(TestKeyboard.typedWithSlip("hello", 4, 0.35f * TestKeyboard.keyWidth, 0f))
            )
        assertThat(r.autocorrect).isNull()
    }

    @Test
    fun touchBetweenKeysPrefersTheNearerCharForCandidates() {
        // A tap on the boundary of 'i' and 'o' typed as 'i': hello is offered.
        val r =
            engine.predictNow(
                request(TestKeyboard.typedWithSlip("helli", 4, 0.45f * TestKeyboard.keyWidth, 0f))
            )
        assertThat(r.texts()).contains("hello")
    }

    @Test
    fun transpositionIsCorrected() {
        val r = engine.predictNow(request(TestKeyboard.typed("teh")))
        assertThat(r.autocorrect?.text).isEqualTo("the")
    }

    @Test
    fun missingLetterIsCorrected() {
        val r = engine.predictNow(request(TestKeyboard.typed("frend")))
        assertThat(r.autocorrect?.text).isEqualTo("friend")
        val doubled = engine.predictNow(request(TestKeyboard.typed("helo")))
        assertThat(doubled.autocorrect?.text).isEqualTo("hello")
    }

    @Test
    fun extraLetterIsCorrected() {
        val r = engine.predictNow(request(TestKeyboard.typed("helllo")))
        assertThat(r.autocorrect?.text).isEqualTo("hello")
        val other = engine.predictNow(request(TestKeyboard.typed("wherre")))
        assertThat(other.autocorrect?.text).isEqualTo("where")
    }

    @Test
    fun missingApostropheIsCorrected() {
        val r = engine.predictNow(request(TestKeyboard.typed("dont")))
        assertThat(r.autocorrect?.text).isEqualTo("don't")
    }

    @Test
    fun accentedInputMatchesBaseLetters() {
        val r = engine.predictNow(request(TestKeyboard.pasted("héllo")))
        assertThat(r.literal?.text).isEqualTo("héllo")
        assertThat(r.texts()).contains("hello")
    }

    @Test
    fun touchlessInputOnlyMatchesItself() {
        val exact = engine.predictNow(request(TestKeyboard.pasted("hello")))
        assertThat(exact.autocorrect).isNull()
        val typo = engine.predictNow(request(TestKeyboard.pasted("helli")))
        // Without touches nothing says "i" is near "o", so correcting is not justified.
        assertThat(typo.autocorrect).isNull()
    }

    @Test
    fun conservativeForRealWords() {
        // "form" is a word; "from" is far more likely but the margin is deliberately large.
        val r = engine.predictNow(request(TestKeyboard.typed("form")))
        assertThat(r.literal?.text).isEqualTo("form")
        assertThat(r.autocorrect).isNull()
    }

    @Test
    fun nonWordTranspositionIsCorrectedToTheFrequentWord() {
        val r = engine.predictNow(request(TestKeyboard.typed("fo")))
        assertThat(r.autocorrect?.text).isEqualTo("of")
    }

    // ------------------------------------------------------------------ completion

    @Test
    fun prefixIsCompletedToLikelyWords() {
        val r = engine.predictNow(request(TestKeyboard.typed("hel"), maxCandidates = 4))
        assertThat(r.texts()).containsAtLeast("hello", "help")
        assertThat(r.autocorrect?.text).isNotEqualTo("hello")
        assertThat(r.candidates.first().text).isAnyOf("hello", "help", "hell", "held")
    }

    @Test
    fun completionsAreNeverAutocorrected() {
        val r = engine.predictNow(request(TestKeyboard.typed("wonde")))
        assertThat(r.texts()).contains("wonder")
        assertThat(r.autocorrect).isNull()
    }

    // ------------------------------------------------------------------ context

    @Test
    fun contextChangesRanking() {
        val typed = TestKeyboard.typed("ther")
        val afterOver = engine.predictNow(request(typed, before = "put it over "))
        val afterLost = engine.predictNow(request(typed, before = "they lost "))
        assertThat(afterOver.candidates.first().text).isEqualTo("there")
        assertThat(afterLost.texts()).contains("their")
        val theirOver = engine.predictRanked(request(typed, before = "put it over ")).candidates
        val theirLost = engine.predictRanked(request(typed, before = "they lost ")).candidates
        fun score(list: List<RankedCandidate>, w: String) = list.first { it.text == w }.score
        assertThat(score(theirLost, "their") - score(theirLost, "there"))
            .isGreaterThan(score(theirOver, "their") - score(theirOver, "there"))
    }

    @Test
    fun contextBreaksAtSentenceBoundariesAndUnknownWords() {
        val lm = LanguageModel(dictionary, ScoringConfig())
        lm.setContext("They went to the store. ")
        assertThat(lm.atSentenceStart).isTrue()
        lm.setContext("i went ")
        assertThat(lm.w1).isEqualTo(dictionary.wordId("went"))
        assertThat(lm.w2).isEqualTo(dictionary.wordId("I"))
        lm.setContext("i went to qzxv ")
        assertThat(lm.w1).isEqualTo(-1)
        lm.setContext("it costs 3.5 dollars. Then, ")
        assertThat(lm.atSentenceStart).isFalse()
        assertThat(lm.w1).isEqualTo(dictionary.wordId("then"))
        lm.setContext("Hello,  \"I'm\" here\n")
        assertThat(lm.atSentenceStart).isTrue()
        lm.setContext("")
        assertThat(lm.atDocumentStart).isTrue()
    }

    @Test
    fun rightContextAddsToTheScore() {
        val typed = TestKeyboard.typed("ther")
        val plain = engine.predictRanked(request(typed, before = "they lost "))
        val withRight = engine.predictRanked(request(typed, before = "they lost ", after = " keys"))
        fun their(p: RankedPrediction) = p.candidates.first { it.text == "their" }
        fun there(p: RankedPrediction) = p.candidates.first { it.text == "there" }
        assertThat(their(plain).breakdown.rightLm).isEqualTo(0f)
        // "their keys" is a seen bigram, "there keys" is not.
        assertThat(their(withRight).breakdown.rightLm)
            .isGreaterThan(there(withRight).breakdown.rightLm)
        assertThat(their(withRight).score - there(withRight).score)
            .isGreaterThan(their(plain).score - there(plain).score)
    }

    @Test
    fun breakdownAddsUpToTheScore() {
        val r = engine.predictRanked(request(TestKeyboard.typed("helli"), maxCandidates = 5))
        val hello = r.candidates.first { it.text == "hello" }
        assertThat(hello.breakdown.total(engine.config.scoring)).isWithin(1e-4f).of(hello.score)
        assertThat(hello.breakdown.editCount).isEqualTo(1)
        assertThat(hello.breakdown.completionChars).isEqualTo(0)
        assertThat(hello.breakdown.spatial).isLessThan(0f)
    }

    // ------------------------------------------------------------------ next word

    @Test
    fun nextWordFollowsTheContext() {
        val r = engine.predictNow(request(ComposingInput.Empty, before = "thank you "))
        assertThat(r.literal).isNull()
        assertThat(r.autocorrect).isNull()
        assertThat(r.texts()).containsAtLeast("very", "for")
        assertThat(r.candidates.first().text).isEqualTo("very")
    }

    @Test
    fun nextWordUsesTrigramThenBigramThenUnigram() {
        val tri = engine.predictNow(request(ComposingInput.Empty, before = "i went to the "))
        assertThat(tri.texts()).contains("store")
        val bi = engine.predictNow(request(ComposingInput.Empty, before = "xyzzy over "))
        assertThat(bi.texts()).contains("there")
        val uni = engine.predictNow(request(ComposingInput.Empty, before = "qzxv "))
        assertThat(uni.texts().take(2)).containsExactly("the", "be").inOrder()
    }

    @Test
    fun nextWordSkipsOffensiveAndEndMarkers() {
        val r =
            engine.predictNow(request(ComposingInput.Empty, before = "hello ", maxCandidates = 8))
        assertThat(r.texts()).doesNotContain("</s>")
        assertThat(r.texts()).doesNotContain("damn")
    }

    @Test
    fun sentenceStartIsCapitalized() {
        val start = engine.predictNow(request(ComposingInput.Empty, before = "I went home. "))
        assertThat(start.texts()).isNotEmpty()
        assertThat(start.texts().all { it[0].isUpperCase() }).isTrue()
        val mid = engine.predictNow(request(ComposingInput.Empty, before = "thank you "))
        assertThat(mid.candidates.first().text[0].isLowerCase()).isTrue()
        val docStart = engine.predictNow(request(ComposingInput.Empty, before = ""))
        assertThat(docStart.candidates.first().text[0].isUpperCase()).isTrue()
    }

    // ------------------------------------------------------------------ casing

    @Test
    fun outputFollowsTheLiteralsCapitalization() {
        val initial = engine.predictNow(request(TestKeyboard.typed("Teh")))
        assertThat(initial.autocorrect?.text).isEqualTo("The")
        val caps = engine.predictNow(request(TestKeyboard.typed("HELI"), maxCandidates = 5))
        assertThat(caps.texts()).contains("HELP")
        assertThat(caps.autocorrect).isNull()
        val lower = engine.predictNow(request(TestKeyboard.typed("iphon")))
        assertThat(lower.texts()).contains("iPhone")
        val initialPhone = engine.predictNow(request(TestKeyboard.typed("Iphon")))
        assertThat(initialPhone.texts()).contains("iPhone")
    }

    @Test
    fun standaloneILowercaseBecomesCapitalI() {
        val r = engine.predictNow(request(TestKeyboard.typed("i")))
        assertThat(r.autocorrect?.text).isEqualTo("I")
        assertThat(r.texts().first()).isEqualTo("I")
        val contraction = engine.predictNow(request(TestKeyboard.typed("i'm")))
        assertThat(contraction.autocorrect?.text).isEqualTo("I'm")
        val blocked =
            engine.predictNow(request(TestKeyboard.typed("i"), autocorrectAllowed = false))
        assertThat(blocked.autocorrect).isNull()
    }

    // ------------------------------------------------------------------ user data

    @Test
    fun userWordProtectsTheLiteral() {
        val withUser =
            engine(userWords = listOf("Rishi")).predictNow(request(TestKeyboard.typed("rishi")))
        assertThat(withUser.literal?.text).isEqualTo("rishi")
        assertThat(withUser.autocorrect).isNull()
    }

    @Test
    fun userWordIsOfferedForNearMisses() {
        val e = engine(userWords = listOf("Rishi"))
        val r = e.predictNow(request(TestKeyboard.typed("rishu")))
        assertThat(r.candidates.first().text).isEqualTo("Rishi")
        assertThat(r.candidates.first().source).isEqualTo(CandidateSource.UserDictionary)
        val completion = e.predictNow(request(TestKeyboard.typed("ris")))
        assertThat(completion.texts()).contains("Rishi")
    }

    @Test
    fun userWordsAreRebuiltWhenTheSnapshotChanges() {
        var words: Collection<String> = emptyList()
        val e = ClassicEngine(dictionary, userWords = { words })
        val typed = TestKeyboard.typed("zorgon")
        assertThat(e.predictNow(request(typed)).autocorrect?.text).isNotEqualTo("Zorgon")
        words = listOf("Zorgon")
        assertThat(e.predictNow(request(typed)).autocorrect).isNull()
        assertThat(e.predictNow(request(TestKeyboard.typed("zorgin"))).texts()).contains("Zorgon")
    }

    @Test
    fun shortcutComesFirstAndIsNeverAutocorrected() {
        val e = engine(shortcuts = mapOf("omw" to "on my way", "Hel" to "Hello there!"))
        val r = e.predictNow(request(TestKeyboard.typed("OMW")))
        assertThat(r.candidates.first().text).isEqualTo("on my way")
        assertThat(r.candidates.first().source).isEqualTo(CandidateSource.Shortcut)
        assertThat(r.autocorrect?.source).isNotEqualTo(CandidateSource.Shortcut)
        val prefix = e.predictNow(request(TestKeyboard.typed("hel"), maxCandidates = 3))
        assertThat(prefix.candidates).hasSize(3)
        assertThat(prefix.candidates.first().text).isEqualTo("Hello there!")
        assertThat(prefix.texts().drop(1)).contains("hello")
    }

    // ------------------------------------------------------------------ safety

    @Test
    fun offensiveWordsAreNotSuggestedButTypedOnesAreKept() {
        val r = engine.predictNow(request(TestKeyboard.typed("dam"), maxCandidates = 8))
        assertThat(r.texts()).doesNotContain("damn")
        val full = engine.predictNow(request(TestKeyboard.typed("damn")))
        assertThat(full.literal?.text).isEqualTo("damn")
        assertThat(full.autocorrect).isNull()
        val typo = engine.predictNow(request(TestKeyboard.typed("damm"), maxCandidates = 8))
        assertThat(typo.texts()).doesNotContain("damn")
    }

    @Test
    fun digitsUrlsAndEmailsAreNeverTouched() {
        for (text in listOf("h3llo", "teh2", "https://x", "a/b", "me@teh")) {
            val r = engine.predictNow(request(TestKeyboard.typed(text)))
            assertThat(r.autocorrect).isNull()
            assertThat(r.literal?.text).isEqualTo(text)
            assertThat(r.candidates).isEmpty()
        }
        val email = engine.predictNow(request(TestKeyboard.typed("teh"), field = FieldClass.Email))
        assertThat(email.autocorrect).isNull()
        val url = engine.predictNow(request(TestKeyboard.typed("teh"), field = FieldClass.Url))
        assertThat(url.autocorrect).isNull()
    }

    @Test
    fun autocorrectRespectsTheRequestAndLiteralLength() {
        val off = engine.predictNow(request(TestKeyboard.typed("teh"), autocorrectAllowed = false))
        assertThat(off.autocorrect).isNull()
        assertThat(off.texts()).contains("the")
        val short = engine.predictNow(request(TestKeyboard.typed("x")))
        assertThat(short.autocorrect).isNull()
    }

    @Test
    fun allCapsAcronymsAreLeftAlone() {
        val r = engine.predictNow(request(TestKeyboard.typed("TEH")))
        assertThat(r.autocorrect).isNull()
        assertThat(r.texts()).contains("THE")
    }

    @Test
    fun thresholdCanBeTunedAtRuntime() {
        val e = engine()
        val typed = TestKeyboard.typed("helli")
        assertThat(e.predictNow(request(typed)).autocorrect?.text).isEqualTo("hello")
        e.autocorrectPolicy.threshold = 100f
        assertThat(e.predictNow(request(typed)).autocorrect).isNull()
        e.autocorrectPolicy.threshold = 0f
        assertThat(e.predictNow(request(typed)).autocorrect?.text).isEqualTo("hello")
    }

    // ------------------------------------------------------------------ phrases

    @Test
    fun phraseCompletionAppearsOnlyAboveTheThresholds() {
        val ctx = request(ComposingInput.Empty, before = "thank ", maxPhraseWords = 4)
        val phrase = engine.predictNow(ctx).phrase
        assertThat(phrase).isNotNull()
        assertThat(phrase!!.isPhrase).isTrue()
        assertThat(phrase.text).isEqualTo("you very much")

        val strictTotal = engine(ClassicConfig(phrase = PhraseConfig(minTotalProbability = 0.99f)))
        assertThat(strictTotal.predictNow(ctx).phrase).isNull()
        val strictStep = engine(ClassicConfig(phrase = PhraseConfig(minStepProbability = 0.99f)))
        assertThat(strictStep.predictNow(ctx).phrase).isNull()
        assertThat(engine.predictNow(ctx.copy(maxPhraseWords = 0)).phrase).isNull()
        assertThat(engine.predictNow(ctx.copy(maxPhraseWords = 2)).phrase?.text)
            .isEqualTo("you very")
    }

    @Test
    fun phraseExtendsTheTopCandidateWhileTyping() {
        val r =
            engine.predictNow(request(TestKeyboard.typed("thak"), before = "", maxPhraseWords = 4))
        assertThat(r.phrase?.text).isEqualTo("thank you very much")
    }

    @Test
    fun noPhraseAtTheStartOfTheDocumentOrFromUnknownContext() {
        val start =
            engine.predictNow(request(ComposingInput.Empty, before = "", maxPhraseWords = 4))
        assertThat(start.phrase).isNull()
        val unknown =
            engine.predictNow(request(ComposingInput.Empty, before = "qzxv ", maxPhraseWords = 4))
        assertThat(unknown.phrase).isNull()
    }

    // ------------------------------------------------------------------ engine plumbing

    @Test
    fun staleRequestsAreAbandoned() {
        var stale = false
        val e = ClassicEngine(dictionary, isStale = { stale })
        val typed = TestKeyboard.typed("helli")
        assertThat(e.predictNow(request(typed)).autocorrect?.text).isEqualTo("hello")
        stale = true
        val r = e.predictNow(request(typed))
        assertThat(r.candidates).isEmpty()
        assertThat(r.literal).isNull()
        assertThat(r.autocorrect).isNull()
        // A request-scoped callback overrides the constructor's.
        val fresh = e.predictNow(request(typed), stale = { false })
        assertThat(fresh.autocorrect?.text).isEqualTo("hello")
    }

    @Test
    fun staleDuringTheSearchAbortsIt() {
        var polls = 0
        val e = ClassicEngine(dictionary)
        val r = e.predictNow(request(TestKeyboard.typed("wonderfull"))) { ++polls > 1 }
        assertThat(r.candidates).isEmpty()
        assertThat(polls).isGreaterThan(1)
    }

    @Test
    fun engineInfoAndSequence() {
        assertThat(engine.info.id).isEqualTo("classic")
        assertThat(engine.info.capabilities)
            .containsExactly(dev.keycorrect.core.engineapi.Capability.Predict)
        assertThat(engine.info.languages).containsExactly("en-US")
        val r = engine.predictNow(request(TestKeyboard.typed("hello")).copy(sequence = 42))
        assertThat(r.sequence).isEqualTo(42)
    }

    @Test
    fun resultsAreDeterministic() {
        val a = engine().predictNow(request(TestKeyboard.typed("wher"), before = "put it "))
        val b = engine().predictNow(request(TestKeyboard.typed("wher"), before = "put it "))
        assertThat(a).isEqualTo(b)
        val again = engine.predictNow(request(TestKeyboard.typed("wher"), before = "put it "))
        assertThat(engine.predictNow(request(TestKeyboard.typed("wher"), before = "put it ")))
            .isEqualTo(again)
    }

    @Test
    fun candidatesNeverIncludeTheLiteralAndAreCapped() {
        val r = engine.predictNow(request(TestKeyboard.typed("hel"), maxCandidates = 5))
        assertThat(r.candidates.size).isAtMost(5)
        assertThat(r.texts()).doesNotContain("hel")
        assertThat(r.texts()).containsNoDuplicates()
        val scores = r.candidates.map { it.score }
        assertThat(scores).isInOrder(Comparator.reverseOrder<Float>())
    }
}

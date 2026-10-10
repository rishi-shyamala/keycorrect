package dev.keycorrect.core.learning.classifier

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.LearningUpdate
import dev.keycorrect.core.learning.LearningUpdate.AddUserWord
import dev.keycorrect.core.learning.LearningUpdate.AutocorrectOutcome
import dev.keycorrect.core.learning.LearningUpdate.BlockWord
import dev.keycorrect.core.learning.LearningUpdate.CasingEvidence
import dev.keycorrect.core.learning.LearningUpdate.CorrectionEvidence
import dev.keycorrect.core.learning.LearningUpdate.NewWordEvidence
import dev.keycorrect.core.learning.LearningUpdate.PhraseFeedback
import dev.keycorrect.core.learning.LearningUpdate.Preference
import dev.keycorrect.core.learning.LearningUpdate.RevertKept
import dev.keycorrect.core.learning.LearningUpdate.SuppressCorrection
import dev.keycorrect.core.learning.LearningUpdate.TouchEvidence
import dev.keycorrect.core.learning.LearningUpdate.WordUsed
import dev.keycorrect.core.learning.PhraseOutcome
import dev.keycorrect.core.learning.RevertKind
import dev.keycorrect.core.learning.UserAction
import org.junit.Test

class OutcomeClassifierRowsTest {
    private val cfg = ClassifierConfig()
    private val classifier = OutcomeClassifier(cfg)

    private inline fun <reified T : LearningUpdate> List<LearningUpdate>.all(): List<T> =
        filterIsInstance<T>()

    // ---- row 1 ----------------------------------------------------------------------------

    @Test
    fun row01_suggestionPicked_strongWordUsedAndPairsAgainstHigherRanked() {
        val out =
            classifier.classify(
                committed(
                    typed = "hel",
                    committed = "help",
                    method = CommitMethod.SuggestionPicked,
                    pickedRank = 2,
                    shown =
                        listOf(seen("hell", 0), seen("held", 1), seen("help", 2), seen("helm", 3)),
                )
            )
        assertThat(out).contains(WordUsed(ctx, "help", cfg.suggestionPickedWeight))
        assertThat(out.all<Preference>().map { it.other })
            .containsExactly("hell", "held") // not helm (ranked below)
        assertThat(out.all<Preference>().map { it.weight }.toSet())
            .containsExactly(cfg.pickedPreferenceWeight)
        assertThat(out.all<Preference>().all { it.preferred == "help" }).isTrue()
        assertThat(out.all<AutocorrectOutcome>()).isEmpty()
    }

    @Test
    fun row01_pickingTopSuggestion_hasNoPairs() {
        val out =
            classifier.classify(
                committed(
                    "hel",
                    "help",
                    CommitMethod.SuggestionPicked,
                    listOf(seen("help", 0), seen("hell", 1)),
                    0,
                )
            )
        assertThat(out.all<Preference>()).isEmpty()
    }

    @Test
    fun row01_pickingACorrection_alsoTeachesThePair() {
        val out =
            classifier.classify(
                committed("teh", "the", CommitMethod.SuggestionPicked, listOf(seen("the", 0)), 0)
            )
        assertThat(out)
            .contains(CorrectionEvidence("teh", "the", BUCKET, cfg.pickedCorrectionDelta))
    }

    @Test
    fun row01_pickingACompletion_doesNotTeachAPair() {
        val out =
            classifier.classify(
                committed("hel", "help", CommitMethod.SuggestionPicked, listOf(seen("help", 0)), 0)
            )
        assertThat(out.all<CorrectionEvidence>()).isEmpty()
    }

    // ---- row 2 ----------------------------------------------------------------------------

    @Test
    fun row02_implicitAccept_visibleSuggestion_fullWeight() {
        val out =
            classifier.classify(
                committed(
                    "because",
                    shown = listOf(seen("because", 0), seen("becomes", 1)),
                    touches = touches("because"),
                )
            )
        assertThat(out).contains(WordUsed(ctx, "because", cfg.typedWordWeight))
        assertThat(out)
            .contains(
                Preference("because", ctx, "because", "becomes", cfg.implicitAcceptPreferenceWeight)
            )
    }

    @Test
    fun row02_implicitAccept_appearedOnLastChar_smallWeight() {
        val out =
            classifier.classify(
                committed("because", shown = listOf(late("because", 0, 7), seen("becomes", 1)))
            )
        val p = out.all<Preference>().single()
        assertThat(p.weight)
            .isWithin(1e-6f)
            .of(cfg.implicitAcceptPreferenceWeight * cfg.lowVisibilityScale)
    }

    @Test
    fun row02_visibleButTooBrief_isSmall() {
        val brief = seen("because", 0).copy(visibleMillis = 250)
        val out =
            classifier.classify(committed("because", shown = listOf(brief, seen("becomes", 1))))
        assertThat(out.all<Preference>().single().weight)
            .isWithin(1e-6f)
            .of(cfg.implicitAcceptPreferenceWeight * cfg.lowVisibilityScale)
    }

    @Test
    fun row02_caseInsensitiveMatch() {
        val out =
            classifier.classify(
                committed("Because", shown = listOf(seen("because", 0), seen("becomes", 1)))
            )
        assertThat(out.all<Preference>().single().other).isEqualTo("becomes")
    }

    // ---- row 3 ----------------------------------------------------------------------------

    @Test
    fun row03_typedSomethingElse_cappedPairAgainstTop() {
        val out =
            classifier.classify(committed("cat", shown = listOf(seen("car", 1), seen("cart", 0))))
        assertThat(out).contains(WordUsed(ctx, "cat", cfg.typedWordWeight))
        val p = out.all<Preference>().single()
        assertThat(p.other).isEqualTo("cart")
        assertThat(p.weight).isAtMost(cfg.ignoredSuggestionWeight)
        assertThat(p.weight).isGreaterThan(0f)
    }

    @Test
    fun row03_unseenTopSuggestion_isDownWeighted() {
        val out = classifier.classify(committed("cat", shown = listOf(late("cart", 0, 3))))
        assertThat(out.all<Preference>().single().weight)
            .isWithin(1e-6f)
            .of(cfg.ignoredSuggestionWeight * cfg.lowVisibilityScale)
    }

    @Test
    fun row03_nothingShown_noPreference() {
        assertThat(classifier.classify(committed("cat")).all<Preference>()).isEmpty()
    }

    // ---- row 4 ----------------------------------------------------------------------------

    @Test
    fun row04_unknownWord_newWordEvidenceWithReducedWordUsed() {
        val out = classifier.classify(committed("kubectl", known = false))
        assertThat(out).contains(NewWordEvidence("kubectl", 7, cfg.newWordEvidenceWeight))
        assertThat(out.all<WordUsed>().single().weight)
            .isWithin(1e-6f)
            .of(cfg.typedWordWeight * cfg.unknownWordUsedScale)
    }

    @Test
    fun row04_unknownWordNearShownSuggestion_isLikelyTypo_noNewWordEvidence() {
        val out =
            classifier.classify(
                committed("recieve", known = false, shown = listOf(seen("receive", 0)))
            )
        assertThat(out.all<NewWordEvidence>()).isEmpty()
        assertThat(out.all<WordUsed>().single().weight)
            .isWithin(1e-6f)
            .of(cfg.typedWordWeight * cfg.unknownWordUsedScale * cfg.likelyTypoScale)
        assertThat(out.all<Preference>()).isEmpty() // not "ignored a suggestion"
    }

    @Test
    fun row04_longUnknownWordTwoEditsFromShown_isLikelyTypo_shortOneIsNot() {
        val long =
            classifier.classify(
                committed("recieved", known = false, shown = listOf(seen("receives", 0)))
            )
        assertThat(long.all<NewWordEvidence>()).isEmpty()
        // A 4-letter word two edits away from the suggestion is not flagged.
        val short =
            classifier.classify(committed("zorp", known = false, shown = listOf(seen("zero", 0))))
        assertThat(short.all<NewWordEvidence>()).hasSize(1)
    }

    @Test
    fun row04_tinyUnknownWord_isNeverANewWord() {
        assertThat(classifier.classify(committed("zq", known = false)).all<NewWordEvidence>())
            .isEmpty()
    }

    @Test
    fun row04_unknownWordFarFromShown_isNotReduced() {
        val out =
            classifier.classify(
                committed("kubectl", known = false, shown = listOf(seen("kubernetes", 0)))
            )
        assertThat(out.all<NewWordEvidence>().single().weight).isEqualTo(cfg.newWordEvidenceWeight)
    }

    @Test
    fun row04_unknownWord_hasNoTouchEvidence() {
        val out =
            classifier.classify(committed("kubectl", known = false, touches = touches("kubectl")))
        assertThat(out.all<TouchEvidence>()).isEmpty()
    }

    // ---- row 5 ----------------------------------------------------------------------------

    @Test
    fun row05_autocorrectKept() {
        val out = classifier.classify(committed("teh", "the", CommitMethod.Autocorrected))
        assertThat(out)
            .containsExactly(
                CorrectionEvidence("teh", "the", BUCKET, cfg.autocorrectKeptDelta),
                WordUsed(ctx, "the", cfg.typedWordWeight),
                AutocorrectOutcome(reverted = false),
            )
    }

    @Test
    fun row05_substitutionOnlyCorrection_usesOnlyUnchangedPositionsForTouch() {
        val t = touches("hlelo")
        val out =
            classifier.classify(
                committed("hlelo", "hello", CommitMethod.Autocorrected, touches = t)
            )
        // typed "hlelo" vs "hello" is a transposition (2 mismatches) -> skipped
        assertThat(out.all<TouchEvidence>()).isEmpty()
        val sub = touches("hxllo")
        val out2 =
            classifier.classify(
                committed("hxllo", "hello", CommitMethod.Autocorrected, touches = sub)
            )
        val te = out2.all<TouchEvidence>().single()
        assertThat(te.samples.map { it.char }).containsExactly('h', 'l', 'l', 'o').inOrder()
        assertThat(te.touchKey).isEqualTo(profile.touchKey)
    }

    @Test
    fun row05_lengthChangingCorrection_noTouch() {
        val out =
            classifier.classify(
                committed("helo", "hello", CommitMethod.Autocorrected, touches = touches("helo"))
            )
        assertThat(out.all<TouchEvidence>()).isEmpty()
    }

    @Test
    fun row05_typedLiteralTouches_skipNullsAndUseProfileKey() {
        val t =
            touches("cat").toMutableList<dev.keycorrect.core.learning.TouchSample?>().also {
                it[1] = null
            }
        val te = classifier.classify(committed("cat", touches = t)).all<TouchEvidence>().single()
        assertThat(te.samples.map { it.char }).containsExactly('c', 't').inOrder()
        assertThat(te.touchKey).isEqualTo("Phone/port/qwerty@54dp")
    }

    // ---- rows 6 / 7 / 23 ----------------------------------------------------------------------

    @Test
    fun row06_immediateBackspace_isAKeep_notAnImmediateAddOrSuppress() {
        val out = classifier.classify(reverted("tset", "test", RevertKind.ImmediateBackspace))
        assertThat(out.all<AddUserWord>()).isEmpty()
        assertThat(out.all<SuppressCorrection>()).isEmpty()
        assertThat(out)
            .containsExactly(
                RevertKept("tset", "test"),
                CorrectionEvidence("tset", "test", BUCKET, -cfg.revertDelta),
                WordUsed(ctx, "tset", cfg.typedWordWeight),
                AutocorrectOutcome(reverted = true),
            )
    }

    @Test
    fun row07_deletedAndRetyped_sameAsRow6() {
        val a = classifier.classify(reverted("tset", "test", RevertKind.DeletedAndRetyped))
        val b = classifier.classify(reverted("tset", "test", RevertKind.ImmediateBackspace))
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun row07_editedLater_toTheLiteral_isTreatedAsRevert() {
        val a =
            classifier.classify(
                reverted("tset", "test", RevertKind.EditedLater, replacement = "tset")
            )
        assertThat(a.all<RevertKept>()).containsExactly(RevertKept("tset", "test"))
        assertThat(a.all<AddUserWord>()).isEmpty()
        assertThat(a.all<SuppressCorrection>()).isEmpty()
    }

    @Test
    fun row07_editedLater_toSomethingElse_movesPairWithoutSuppressing() {
        val out =
            classifier.classify(
                reverted("tset", "test", RevertKind.EditedLater, replacement = "set")
            )
        assertThat(out)
            .containsExactly(
                CorrectionEvidence("tset", "test", BUCKET, -cfg.editedLaterNegativeDelta),
                CorrectionEvidence("tset", "set", BUCKET, cfg.editedLaterPositiveDelta),
                WordUsed(ctx, "set", cfg.typedWordWeight),
            )
        // Ranking errors (right idea, wrong word) don't move the adaptive threshold by default.
        val counted = OutcomeClassifier(cfg.copy(rankingRevertsCount = true))
        assertThat(
                counted.classify(
                    reverted("tset", "test", RevertKind.EditedLater, replacement = "set")
                )
            )
            .contains(AutocorrectOutcome(reverted = true))
    }

    @Test
    fun row23_retypedLiteralInLaterSession_usesRevertPath_andReAddIsIdempotentForModel() {
        // Later session: the literal is now a known word, so autocorrect no longer fires and plain
        // typing of it is just a known-word commit.
        val out = classifier.classify(committed("tset", known = true))
        assertThat(out.all<NewWordEvidence>()).isEmpty()
        assertThat(out).contains(WordUsed(ctx, "tset", cfg.typedWordWeight))
    }

    // ---- row 22 ----------------------------------------------------------------------------

    @Test
    fun row22_tieKept_weakerPositivePair() {
        val out =
            classifier.classify(
                committed("tset", "test", CommitMethod.Autocorrected, tieRunnerUp = "set")
            )
        assertThat(out).contains(CorrectionEvidence("tset", "test", BUCKET, cfg.tieKeptDelta))
        assertThat(out).contains(AutocorrectOutcome(false))
    }

    @Test
    fun row22_switchedToRunnerUp_movesPair_noImplicitAddOrSuppress() {
        val out =
            classifier.classify(
                reverted("tset", "test", RevertKind.SwitchedToSuggestion, replacement = "set")
            )
        assertThat(out)
            .containsExactly(
                CorrectionEvidence("tset", "test", BUCKET, -cfg.switchNegativeDelta),
                CorrectionEvidence("tset", "set", BUCKET, cfg.switchPositiveDelta),
                Preference("tset", ctx, "set", "test", cfg.switchPreferenceWeight),
            )
        assertThat(out.all<AddUserWord>()).isEmpty()
        assertThat(out.all<SuppressCorrection>()).isEmpty()
    }

    // ---- row 8 ----------------------------------------------------------------------------

    @Test
    fun row08_literalPicked_knownWord_strongUseSuppressesTopCorrection() {
        val out =
            classifier.classify(
                committed(
                    "form",
                    method = CommitMethod.LiteralPicked,
                    shown = listOf(seen("from", 0), seen("forms", 1)),
                )
            )
        assertThat(out).contains(WordUsed(ctx, "form", cfg.literalPickedWeight))
        assertThat(out).contains(SuppressCorrection("form", "from"))
        assertThat(out)
            .contains(CorrectionEvidence("form", "from", BUCKET, -cfg.literalRejectDelta))
        assertThat(out.all<AddUserWord>()).isEmpty()
    }

    @Test
    fun row08_literalPicked_unknownWord_strongNewWordEvidence_noAutoAdd() {
        val out =
            classifier.classify(
                committed(
                    "zorp",
                    method = CommitMethod.LiteralPicked,
                    known = false,
                    shown = listOf(seen("zip", 0)),
                )
            )
        assertThat(out).contains(NewWordEvidence("zorp", 7, cfg.literalNewWordEvidenceWeight))
        assertThat(out.all<AddUserWord>()).isEmpty()
        assertThat(out).contains(SuppressCorrection("zorp", "zip"))
    }

    @Test
    fun row08_literalPicked_doesNotSuppressCompletions() {
        val out =
            classifier.classify(
                committed(
                    "hel",
                    method = CommitMethod.LiteralPicked,
                    shown = listOf(seen("help", 0)),
                )
            )
        assertThat(out.all<SuppressCorrection>()).isEmpty()
    }

    // ---- rows 9 / 10 ------------------------------------------------------------------------

    @Test
    fun row09_quickDeleteOfPickedSuggestion_undoesAccept() {
        val out = classifier.classify(replaced("their", "there", picked = true, millis = 1_500))
        assertThat(out.all<WordUsed>().first())
            .isEqualTo(WordUsed(ctx, "their", -cfg.suggestionPickedWeight * cfg.misTapUndoFraction))
        assertThat(out.all<CorrectionEvidence>()).isEmpty()
    }

    @Test
    fun row09_slowReplacementOfPicked_isOrdinaryReplacement() {
        val out = classifier.classify(replaced("their", "there", picked = true, millis = 4_000))
        assertThat(out.all<WordUsed>().none { it.weight < 0f }).isTrue()
    }

    @Test
    fun row10_smallEditDistance_missedCorrection() {
        val out = classifier.classify(replaced("recieve", "receive"))
        assertThat(out)
            .contains(CorrectionEvidence("recieve", "receive", BUCKET, cfg.missedCorrectionDelta))
        assertThat(out).contains(WordUsed(ctx, "receive", cfg.typedWordWeight))
    }

    @Test
    fun row10_rewording_wordUsedOnly() {
        val out = classifier.classify(replaced("great", "wonderful"))
        assertThat(out).containsExactly(WordUsed(ctx, "wonderful", cfg.typedWordWeight))
    }

    @Test
    fun row10_veryShortOriginal_neverMakesAPair() {
        val out = classifier.classify(replaced("to", "so"))
        assertThat(out.all<CorrectionEvidence>()).isEmpty()
    }

    // ---- row 11 ----------------------------------------------------------------------------

    @Test
    fun row11_neverSuggest_blocks() {
        val out = classifier.classify(UserAction(1, profile, UserAction.Kind.NeverSuggest, "duck"))
        assertThat(out).containsExactly(BlockWord("duck"))
    }

    @Test
    fun row11_addToDictionary_explicit() {
        val out =
            classifier.classify(UserAction(1, profile, UserAction.Kind.AddToDictionary, "Rishi"))
        assertThat(out).containsExactly(AddUserWord("Rishi", AddUserWord.Reason.Explicit))
    }

    // ---- row 12 ----------------------------------------------------------------------------

    @Test
    fun row12_gesture_wordUsedOnly() {
        val out =
            classifier.classify(
                committed("hello", method = CommitMethod.Gesture, touches = touches("hello"))
            )
        assertThat(out).containsExactly(WordUsed(ctx, "hello", cfg.typedWordWeight))
    }

    // ---- row 13 ----------------------------------------------------------------------------

    @Test
    fun row13_midSentenceCamelCase_emitsCasing() {
        val out = classifier.classify(committed("iPhone"))
        assertThat(out).contains(CasingEvidence("iPhone"))
    }

    @Test
    fun row13_midSentenceProperNoun_emitsCasing() {
        assertThat(classifier.classify(committed("Paris"))).contains(CasingEvidence("Paris"))
    }

    @Test
    fun row13_sentenceInitialCapital_isTrivial() {
        val out = classifier.classify(committed("Paris", sentenceInitial = true))
        assertThat(out.all<CasingEvidence>()).isEmpty()
    }

    @Test
    fun row13_shoutingLowercaseAndLoneI_areTrivial() {
        for (w in listOf("HELLO", "hello", "I")) {
            assertThat(classifier.classify(committed(w)).all<CasingEvidence>()).isEmpty()
        }
    }

    @Test
    fun row13_sentenceInitialMcDonalds_stillEmits() {
        val out = classifier.classify(committed("McDonald's", sentenceInitial = true))
        assertThat(out).contains(CasingEvidence("McDonald's"))
    }

    @Test
    fun row13_notInEmailFields() {
        val out = classifier.classify(committed("iPhone", field = FieldClass.Email))
        assertThat(out.all<CasingEvidence>()).isEmpty()
    }

    // ---- row 14 ----------------------------------------------------------------------------

    @Test
    fun row14_negativeMassIsCapped() {
        val tight = OutcomeClassifier(cfg.copy(maxNegativeMassPerEvent = 1.0f))
        val out = tight.classify(reverted("tset", "test", RevertKind.ImmediateBackspace))
        assertThat(out.all<CorrectionEvidence>().single().delta).isWithin(1e-6f).of(-1.0f)
        // positives untouched
        assertThat(out).contains(WordUsed(ctx, "tset", cfg.typedWordWeight))
    }

    @Test
    fun row14_manyPairsAreScaledTogether() {
        val shown = (0..5).map { seen("w$it", it) } + seen("target", 6)
        val tight = OutcomeClassifier(cfg.copy(maxNegativeMassPerEvent = 0.5f))
        val out =
            tight.classify(
                committed("tar", "target", CommitMethod.SuggestionPicked, shown, pickedRank = 6)
            )
        assertThat(out.all<Preference>().sumOf { it.weight.toDouble() }).isAtMost(0.5001)
    }

    // ---- row 15 ----------------------------------------------------------------------------

    @Test
    fun row15_bulkOrPasteLikeCommits_areIgnored() {
        for (w in
            listOf(
                "hello world this is a paste",
                "https://example.com/x",
                "x".repeat(200),
                "   ",
                "1234",
            )) {
            assertThat(classifier.classify(committed(w))).isEmpty()
        }
    }

    @Test
    fun row15_predictionPicked_reducedWeight() {
        val out = classifier.classify(committed("", "morning", CommitMethod.PredictionPicked))
        assertThat(out).containsExactly(WordUsed(ctx, "morning", cfg.predictionPickedWeight))
    }

    // ---- row 16 ----------------------------------------------------------------------------

    @Test
    fun row16_voiceScalesAllWeights() {
        val typedOut = classifier.classify(committed("kubectl", known = false))
        val voiceOut =
            classifier.classify(committed("kubectl", known = false, method = CommitMethod.Voice))
        assertThat(voiceOut.all<NewWordEvidence>().single().weight)
            .isWithin(1e-6f)
            .of(typedOut.all<NewWordEvidence>().single().weight * cfg.voiceScale)
        assertThat(voiceOut.all<WordUsed>().single().weight)
            .isWithin(1e-6f)
            .of(typedOut.all<WordUsed>().single().weight * cfg.voiceScale)
        assertThat(voiceOut.all<TouchEvidence>()).isEmpty()
    }

    // ---- row 17 (privacy is upstream) -----------------------------------------------------------

    @Test
    fun row17_privacyGateIsUpstream_classifierHasNoStateBetweenCalls() {
        val e = committed("hello", shown = listOf(seen("help", 0)))
        assertThat(classifier.classify(e)).isEqualTo(classifier.classify(e))
    }

    // ---- rows 18-20 ----------------------------------------------------------------------------

    @Test
    fun row18_phraseFullyAccepted_wordsInContext() {
        val out =
            classifier.classify(
                PhraseOutcome(1, profile, listOf("see"), listOf("you", "later"), 2, null)
            )
        assertThat(out)
            .containsExactly(
                WordUsed(listOf("see"), "you", cfg.phraseAcceptedWordWeight),
                WordUsed(listOf("see", "you"), "later", cfg.phraseAcceptedWordWeight),
                PhraseFeedback(2, 2),
            )
    }

    @Test
    fun row19_partialAcceptWithDivergence() {
        val out =
            classifier.classify(
                PhraseOutcome(1, profile, listOf("see"), listOf("you", "later", "today"), 1, "soon")
            )
        assertThat(out).contains(WordUsed(listOf("see"), "you", cfg.phraseAcceptedWordWeight))
        assertThat(out)
            .contains(WordUsed(listOf("see", "you"), "later", -cfg.phraseRejectedWordWeight))
        assertThat(out).contains(WordUsed(listOf("see", "you"), "soon", cfg.typedWordWeight))
        assertThat(out)
            .contains(
                Preference("", listOf("see", "you"), "soon", "later", cfg.phrasePreferenceWeight)
            )
        assertThat(out).contains(PhraseFeedback(3, 1))
    }

    @Test
    fun row20_trailingWordsDeleted_rejectsFirstDroppedWordOnly() {
        val out =
            classifier.classify(
                PhraseOutcome(1, profile, emptyList(), listOf("on", "my", "way"), 1, null)
            )
        assertThat(out)
            .containsExactly(
                WordUsed(emptyList(), "on", cfg.phraseAcceptedWordWeight),
                WordUsed(listOf("on"), "my", -cfg.phraseRejectedWordWeight),
                PhraseFeedback(3, 1),
            )
    }

    @Test
    fun row18_20_phraseFullyRejected() {
        val out =
            classifier.classify(PhraseOutcome(1, profile, listOf("a"), listOf("b", "c"), 0, "d"))
        assertThat(out.all<PhraseFeedback>().single()).isEqualTo(PhraseFeedback(2, 0))
        assertThat(out.all<WordUsed>().map { it.word }).containsExactly("b", "d")
    }

    // ---- row 21 ----------------------------------------------------------------------------

    @Test
    fun row21_pairsAreBucketedByPreviousWordAndField() {
        val a = classifier.classify(reverted("their", "there", RevertKind.ImmediateBackspace))
        val e2 =
            reverted("their", "there", RevertKind.ImmediateBackspace)
                .copy(
                    context = listOf("over"),
                    profile = profile.copy(field = FieldClass.Email),
                )
        val b = classifier.classify(e2)
        assertThat(a.all<CorrectionEvidence>().single().contextBucket).isEqualTo("the|Text")
        assertThat(b.all<CorrectionEvidence>().single().contextBucket).isEqualTo("over|Email")
    }
}

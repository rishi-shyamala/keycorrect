package dev.keycorrect.tools.eval

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.learning.AutocorrectReverted
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.LearningUpdate
import dev.keycorrect.core.learning.RevertKind
import dev.keycorrect.core.learning.WordCommitted
import dev.keycorrect.core.learning.WordReplaced
import dev.keycorrect.core.learning.classifier.ClassifierConfig
import dev.keycorrect.core.learning.classifier.OutcomeClassifier
import java.util.Random
import org.junit.Test

class LearnTest {
    private val keyboard = EvalKeyboard()
    private val known = setOf("hello", "world", "thanks")
    private val isKnown: (String) -> Boolean = { it.lowercase() in known }
    private val builder = EventBuilder(keyboard = keyboard, isKnown = isKnown)
    private val noTypos =
        TypistConfig(omissionRate = 0f, insertionRate = 0f, transpositionRate = 0f)

    private fun record(
        target: String,
        literal: String = target,
        strips: List<List<String>> = listOf(listOf("x")),
        autocorrect: String? = null,
        runnerUp: String? = null,
        reaction: Reaction,
    ): WordRecord {
        val typed =
            Typist(keyboard, noTypos.copy(sigmaX = 0f, sigmaY = 0f)).type(literal, Random(1))
        return WordRecord(
            10_000L,
            1L,
            listOf("a"),
            false,
            typed,
            target,
            strips,
            autocorrect,
            runnerUp,
            reaction,
        )
    }

    // ---- reaction model

    private fun situation(
        target: String = "Sami",
        literal: String = "Sami",
        strip: List<String> = emptyList(),
        autocorrect: String? = null,
        runnerUp: String? = null,
    ) = Situation(target, literal, strip, autocorrect, runnerUp)

    private val sure =
        ReactionConfig(notice = 1f, immediateBackspaceShare = 1f, tapProbability = 1f)

    @Test
    fun correctAutocorrectIsAccepted() {
        val r =
            ReactionModel(sure).react(situation("hello", "helo", autocorrect = "hello"), Random(1))
        assertThat(r).isEqualTo(Reaction.Accept("hello"))
    }

    @Test
    fun changedPersonalWordIsRevertedImmediately() {
        val r = ReactionModel(sure).react(situation("Sami", "Sami", autocorrect = "Sam"), Random(1))
        assertThat(r).isEqualTo(Reaction.Revert(RevertKind.ImmediateBackspace, "Sami"))
    }

    @Test
    fun changedPersonalWordIsDeletedAndRetypedWhenShareIsZero() {
        val m = ReactionModel(sure.copy(immediateBackspaceShare = 0f))
        val r = m.react(situation("Sami", "Sami", autocorrect = "Sam"), Random(1))
        assertThat(r).isEqualTo(Reaction.Revert(RevertKind.DeletedAndRetyped, "Sami"))
    }

    @Test
    fun unnoticedWrongCorrectionStands() {
        val m = ReactionModel(sure.copy(notice = 0f))
        val r = m.react(situation("Sami", "Sami", autocorrect = "Sam"), Random(1))
        assertThat(r).isEqualTo(Reaction.Accept("Sam"))
    }

    @Test
    fun runnerUpTargetIsSwitchedTo() {
        val r =
            ReactionModel(sure)
                .react(situation("cat", "cst", autocorrect = "cut", runnerUp = "cat"), Random(1))
        assertThat(r).isInstanceOf(Reaction.Switch::class.java)
        assertThat(r.finalText).isEqualTo("cat")
    }

    @Test
    fun targetInStripButNotAppliedIsTappedOrIgnored() {
        val s = situation("Sami", "Sqmi", strip = listOf("Sam", "Sami"))
        assertThat(ReactionModel(sure).react(s, Random(1))).isEqualTo(Reaction.Tap(1, "Sami"))
        val never = sure.copy(tapProbability = 0f, retypeAfterIgnoredStrip = 1f)
        assertThat(ReactionModel(never).react(s, Random(1))).isEqualTo(Reaction.Retype("Sami"))
        val keep = sure.copy(tapProbability = 0f, retypeAfterIgnoredStrip = 0f)
        assertThat(ReactionModel(keep).react(s, Random(1))).isEqualTo(Reaction.Accept("Sqmi"))
    }

    @Test
    fun reactionConsumesFixedRandomness() {
        val a = Random(7)
        val b = Random(7)
        ReactionModel().react(situation("a", "a"), a)
        ReactionModel().react(situation("Sami", "Sqmi", strip = listOf("Sami")), b)
        assertThat(a.nextInt()).isEqualTo(b.nextInt())
    }

    // ---- event construction

    @Test
    fun touchesAreInKeyUnitsFromTheKeyCentre() {
        val typed =
            Typist(keyboard, noTypos.copy(sigmaX = 0f, sigmaY = 0f, biasX = -0.1f, biasY = 0.2f))
                .type("ab", Random(1))
        val t = builder.touches(typed)
        assertThat(t).hasSize(2)
        assertThat(t[0]!!.char).isEqualTo('a')
        assertThat(t[0]!!.dx).isWithin(1e-3f).of(-0.1f)
        assertThat(t[0]!!.dy).isWithin(1e-3f).of(0.2f)
    }

    @Test
    fun shownVisibilityGrowsWithEarlierAppearance() {
        val strips = listOf(listOf("a"), listOf("a", "b"), listOf("a", "b"), listOf("a", "c"))
        val shown = builder.shown(strips)
        val a = shown.first { it.text == "a" }
        val c = shown.first { it.text == "c" }
        assertThat(a.shownAtChars).isEqualTo(1)
        assertThat(c.shownAtChars).isEqualTo(4)
        assertThat(a.visibleMillis).isGreaterThan(c.visibleMillis)
    }

    @Test
    fun shownAddsTheRunnerUpWhenMissing() {
        val shown = builder.shown(listOf(listOf("a")), extra = "z")
        assertThat(shown.map { it.text }).containsExactly("a", "z").inOrder()
    }

    @Test
    fun acceptWithoutAutocorrectIsOneTypedCommit() {
        val e = builder.build(record("hello", reaction = Reaction.Accept("hello")))
        assertThat(e).hasSize(1)
        val c = e[0] as WordCommitted
        assertThat(c.method).isEqualTo(CommitMethod.Typed)
        assertThat(c.typedIsKnown).isTrue()
    }

    @Test
    fun immediateRevertEmitsAutocorrectThenRevert() {
        val r =
            record(
                "Sami",
                autocorrect = "Sam",
                runnerUp = "Samy",
                reaction = Reaction.Revert(RevertKind.ImmediateBackspace, "Sami"),
            )
        val e = builder.build(r)
        assertThat(e).hasSize(2)
        val c = e[0] as WordCommitted
        assertThat(c.method).isEqualTo(CommitMethod.Autocorrected)
        assertThat(c.committed).isEqualTo("Sam")
        assertThat(c.tieRunnerUp).isEqualTo("Samy")
        val rv = e[1] as AutocorrectReverted
        assertThat(rv.kind).isEqualTo(RevertKind.ImmediateBackspace)
        assertThat(rv.timeMillis).isEqualTo(10_400L)
        assertThat(rv.replacement).isEqualTo("Sami")
    }

    @Test
    fun switchEmitsRevertAndPickedCommit() {
        val r =
            record(
                "cat",
                "cst",
                strips = listOf(listOf("cut", "cat")),
                autocorrect = "cut",
                runnerUp = "cat",
                reaction = Reaction.Switch(1, "cat"),
            )
        val e = builder.build(r)
        assertThat(e).hasSize(3)
        assertThat((e[1] as AutocorrectReverted).kind).isEqualTo(RevertKind.SwitchedToSuggestion)
        val p = e[2] as WordCommitted
        assertThat(p.method).isEqualTo(CommitMethod.SuggestionPicked)
        assertThat(p.pickedRank).isEqualTo(1)
    }

    @Test
    fun retypeEmitsTypedCommitAndWordReplaced() {
        val e = builder.build(record("hello", "helko", reaction = Reaction.Retype("hello")))
        assertThat(e[0]).isInstanceOf(WordCommitted::class.java)
        val w = e[1] as WordReplaced
        assertThat(w.original).isEqualTo("helko")
        assertThat(w.replacement).isEqualTo("hello")
    }

    @Test
    fun revertTeachesTheClassifierToKeepTheWord() {
        val r =
            record(
                "Sami",
                autocorrect = "Sam",
                reaction = Reaction.Revert(RevertKind.ImmediateBackspace, "Sami"),
            )
        val classifier = OutcomeClassifier(ClassifierConfig())
        val updates = builder.build(r).flatMap { classifier.classify(it) }
        assertThat(updates.filterIsInstance<LearningUpdate.AddUserWord>().map { it.word })
            .contains("Sami")
        assertThat(updates.filterIsInstance<LearningUpdate.SuppressCorrection>()).isNotEmpty()
    }

    // ---- personal stream and typist bias

    private fun sentences(n: Int) =
        (0 until n).map {
            ParsedSentence(
                listOf(
                    Token("", "We", true, true),
                    Token(" ", "like", true, true),
                    Token(" ", "dogs", true, true),
                ),
                ".",
            )
        }

    @Test
    fun personalStreamIsDeterministicAndHitsTheRate() {
        val user =
            PersonalUser(
                PersonalUserConfig(personalRate = 0.2f, phraseRate = 0f),
                listOf("Ziri", "Sami", "blorp"),
                emptyList(),
            )
        val a = PersonalStream.build(sentences(2000), user, 6000, isKnown, Random(3))
        val b = PersonalStream.build(sentences(2000), user, 6000, isKnown, Random(3))
        assertThat(a).isEqualTo(b)
        val tokens = a.flatMap { it.tokens }
        val share = tokens.count { user.isPersonal(it.text) }.toDouble() / tokens.size
        assertThat(share).isWithin(0.03).of(0.2)
        assertThat(tokens.filter { user.isPersonal(it.text) }.all { !it.inDictionary }).isTrue()
    }

    @Test
    fun habitualPhrasesStartSentences() {
        val user =
            PersonalUser(
                PersonalUserConfig(personalRate = 0f, phraseRate = 1f),
                listOf("Sami"),
                listOf(listOf("Thanks", "Sami")),
            )
        val out = PersonalStream.build(sentences(5), user, 15, isKnown, Random(1))
        assertThat(out[0].tokens.take(2).map { it.text })
            .containsExactly("Thanks", "Sami")
            .inOrder()
        assertThat(out[0].tokens[2].gap).isEqualTo(", ")
    }

    @Test
    fun zipfSamplingFavoursTheTopWord() {
        val user = PersonalUser(PersonalUserConfig(), listOf("a", "b", "c", "d"), emptyList())
        val rng = Random(1)
        val counts = (0 until 4000).groupingBy { user.sample(rng) }.eachCount()
        assertThat(counts.getValue("a")).isGreaterThan(counts.getValue("d") * 2)
    }

    @Test
    fun typistBiasShiftsTheMeanTouch() {
        val t = Typist(keyboard, noTypos.copy(biasX = -0.1f, biasY = 0.2f))
        val key = keyboard.key('g')!!
        var sx = 0.0
        var sy = 0.0
        val n = 3000
        repeat(n) {
            val p = t.type("g", Random(it.toLong())).input.touches[0]!!
            sx += (p.x - key.centerX) / key.width
            sy += (p.y - key.centerY) / key.height
        }
        assertThat(sx / n).isWithin(0.03).of(-0.1)
        assertThat(sy / n).isWithin(0.03).of(0.2)
    }
}

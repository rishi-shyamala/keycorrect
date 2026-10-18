package dev.keycorrect.core.voice

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.voice.DictationEdit.ClearComposing
import dev.keycorrect.core.voice.DictationEdit.Commit
import dev.keycorrect.core.voice.DictationEdit.Composing
import dev.keycorrect.core.voice.DictationEdit.DeleteLastDictation
import dev.keycorrect.core.voice.DictationEdit.NewLine
import org.junit.Test

class DictationAssemblerTest {
    /** The text a final adds after [before], applying only commits and newlines. */
    private fun dictate(
        before: String?,
        vararg finals: String,
        cap: Boolean = true,
        space: Boolean = true,
    ): String {
        val a = DictationAssembler(before, cap, space)
        finals.forEach { a.onFinal(it) }
        return a.committedText
    }

    @Test
    fun partialsShowComposingAndFinalCommits() {
        val a = DictationAssembler("")
        assertThat(a.onPartial("hello", 0)).containsExactly(Composing("Hello"))
        assertThat(a.onPartial("hello", 0)).isEmpty() // unchanged
        assertThat(a.onPartial("hello world", 0)).containsExactly(Composing("Hello world"))
        assertThat(a.onFinal("hello world"))
            .containsExactly(ClearComposing, Commit("Hello world"))
            .inOrder()
        assertThat(a.committedText).isEqualTo("Hello world")
    }

    @Test
    fun stablePrefixIsCommittedOnceAndNeverDuplicated() {
        val a = DictationAssembler("")
        assertThat(a.onPartial("hello world how", 11))
            .containsExactly(Commit("Hello world"), Composing(" how"))
            .inOrder()
        assertThat(a.onPartial("hello world how are", 11)).containsExactly(Composing(" how are"))
        assertThat(a.onPartial("hello world how are you", 19))
            .containsExactly(Commit(" how are"), Composing(" you"))
            .inOrder()
        assertThat(a.onFinal("hello world how are you"))
            .containsExactly(ClearComposing, Commit(" you"))
            .inOrder()
        assertThat(a.committedText).isEqualTo("Hello world how are you")
    }

    @Test
    fun stablePrefixInsideAWordWaitsForTheWordBoundary() {
        val a = DictationAssembler("")
        assertThat(a.onPartial("hello wor", 8))
            .containsExactly(Commit("Hello"), Composing(" wor"))
            .inOrder()
    }

    @Test
    fun revisionsInsideTheUnstablePartReplaceTheComposingText() {
        val a = DictationAssembler("")
        a.onPartial("hello world", 5)
        assertThat(a.onPartial("hello word", 5)).containsExactly(Composing(" word"))
        assertThat(a.onPartial("hello", 5))
            .containsExactly(ClearComposing) // shrank back to the stable part
        assertThat(a.onPartial("hello a", 5)).containsExactly(Composing(" a"))
        assertThat(a.onFinal("hello a thing"))
            .containsExactly(ClearComposing, Commit(" a thing"))
            .inOrder()
        assertThat(a.committedText).isEqualTo("Hello a thing")
    }

    @Test
    fun aFinalShorterThanWhatIsCommittedAddsNothing() {
        val a = DictationAssembler("")
        a.onPartial("hello world", 11)
        assertThat(a.onFinal("hello")).isEmpty()
        assertThat(a.committedText).isEqualTo("Hello world")
    }

    @Test
    fun spacingBeforeTheDictation() {
        assertThat(dictate("Hello", "there")).isEqualTo(" there")
        assertThat(dictate("Hello,", "there")).isEqualTo(" there")
        assertThat(dictate("Hello ", "there")).isEqualTo("there")
        assertThat(dictate("Hello\n", "There")).isEqualTo("There")
        assertThat(dictate("", "hello")).isEqualTo("Hello")
        assertThat(dictate("(", "see this")).isEqualTo("see this")
        assertThat(dictate(null, "hello")).isEqualTo("Hello")
        assertThat(dictate("Hello", "there", space = false)).isEqualTo("there")
    }

    @Test
    fun spacingBetweenSuccessiveFinals() {
        assertThat(dictate("", "hello there", "how are you")).isEqualTo("Hello there how are you")
        assertThat(dictate("", "hello there", "how are you", space = false))
            .isEqualTo("Hello therehow are you")
    }

    @Test
    fun capitalisation() {
        assertThat(dictate("Done. ", "next one")).isEqualTo("Next one")
        assertThat(dictate("Done.", "next one")).isEqualTo(" Next one")
        assertThat(dictate("Really? ", "yes")).isEqualTo("Yes")
        assertThat(dictate("Wow!\n", "yes")).isEqualTo("Yes")
        assertThat(dictate("I think ", "so")).isEqualTo("so")
        assertThat(dictate("(", "see")).isEqualTo("see")
        assertThat(dictate("Hi. ", "next one", cap = false)).isEqualTo("next one")
        assertThat(dictate("", "first", "second")).isEqualTo("First second")
    }

    @Test
    fun capitalisationAfterDictatedSentenceEnds() {
        assertThat(dictate("", "hello period how are you question mark yes"))
            .isEqualTo("Hello. How are you? Yes")
        assertThat(dictate("", "stop exclamation mark go")).isEqualTo("Stop! Go")
    }

    @Test
    fun engineTextThatIsAlreadyPunctuatedIsOnlyFixedAtTheStart() {
        assertThat(dictate("", "Hello, world. This is iPhone."))
            .isEqualTo("Hello, world. This is iPhone.")
        assertThat(dictate("Hi.", "Hello there.")).isEqualTo(" Hello there.")
        assertThat(dictate("Hi", "hello there."))
            .isEqualTo(" hello there.") // not sentence start: untouched
        assertThat(dictate("Hi. ", "hello there.")).isEqualTo("Hello there.")
        // "e.g. x" has attached punctuation, so the word after "e.g." keeps its case.
        assertThat(dictate("", "use tools, e.g. kubectl")).isEqualTo("Use tools, e.g. kubectl")
        // A second, unpunctuated final still gets capitalised after the first one's period.
        assertThat(dictate("", "Hello there.", "next thing")).isEqualTo("Hello there. Next thing")
    }

    @Test
    fun spokenCommands() {
        val a = DictationAssembler("")
        assertThat(a.onFinal("hello new line world"))
            .containsExactly(
                ClearComposing.takeIf { false } ?: Commit("Hello"),
                NewLine,
                Commit("World"),
            )
            .inOrder()
        assertThat(dictate("", "a newline b")).isEqualTo("A\nB")
        assertThat(dictate("", "a new paragraph b")).isEqualTo("A\n\nB")
        assertThat(dictate("", "yes comma sir period")).isEqualTo("Yes, sir.")
        assertThat(dictate("", "ok full stop")).isEqualTo("Ok.")
        assertThat(dictate("", "really question mark")).isEqualTo("Really?")
        assertThat(dictate("", "wow exclamation point")).isEqualTo("Wow!")
        assertThat(dictate("", "wow exclamation mark")).isEqualTo("Wow!")
        assertThat(dictate("", "to do colon milk semicolon eggs")).isEqualTo("To do: milk; eggs")
        assertThat(dictate("", "Hello COMMA world")).isEqualTo("Hello, world")
        assertThat(dictate("", "Hello. New line. World.")).isEqualTo("Hello.\nWorld.")
        assertThat(dictate("", "the new lines are long")).isEqualTo("The new lines are long")
        assertThat(dictate("", "a period")).isEqualTo("A.")
    }

    @Test
    fun commandsSplitAcrossAStablePrefixWaitForTheirNextWord() {
        val a = DictationAssembler("")
        assertThat(a.onPartial("hello new line world", 9))
            .containsExactly(Commit("Hello"), Composing(" new line world"))
            .inOrder() // "new" was held back; the composing preview shows words as spoken
        assertThat(a.onFinal("hello new line world"))
            .containsExactly(ClearComposing, NewLine, Commit("World"))
            .inOrder()
        assertThat(a.committedText).isEqualTo("Hello\nWorld")
    }

    @Test
    fun deleteThatRemovesTheLastDictationOnlyAsAWholeUtterance() {
        val a = DictationAssembler("Hi.")
        a.onFinal("first thing")
        a.onFinal("second thing")
        assertThat(a.committedText).isEqualTo(" First thing second thing")
        assertThat(a.onFinal("Delete that.")).containsExactly(DeleteLastDictation)
        assertThat(a.lastDeletedText).isEqualTo(" second thing")
        assertThat(a.committedText).isEqualTo(" First thing")
        // Cursor state is rewound: the next one follows "first thing".
        a.onFinal("third")
        assertThat(a.committedText).isEqualTo(" First thing third")
        // Not alone: typed as text.
        val b = DictationAssembler("")
        b.onFinal("please delete that file")
        assertThat(b.committedText).isEqualTo("Please delete that file")
        // Nothing to delete: no edit.
        assertThat(DictationAssembler("x").onFinal("delete that")).isEmpty()
    }

    @Test
    fun deleteThatDuringPartialsDoesNotCommitDelete() {
        val a = DictationAssembler("")
        a.onFinal("hello")
        assertThat(a.onPartial("delete", 6)).containsExactly(Composing(" delete"))
        assertThat(a.onPartial("delete that", 6)).containsExactly(Composing(" delete that"))
        assertThat(a.onFinal("delete that"))
            .containsExactly(ClearComposing, DeleteLastDictation)
            .inOrder()
        assertThat(a.committedText).isEmpty()
    }

    @Test
    fun cancelClearsComposingAndKeepsWhatWasCommitted() {
        val a = DictationAssembler("")
        a.onPartial("hello world", 5)
        assertThat(a.cancel()).containsExactly(ClearComposing)
        assertThat(a.committedText).isEqualTo("Hello")
        assertThat(a.cancel()).isEmpty()
    }

    @Test
    fun customCommandsCanBeAdded() {
        val extra =
            SpokenCommands.DEFAULT +
                listOf(SpokenCommand("smiley face", CommandAction.Punctuation(":)")))
        val a = DictationAssembler("", commands = extra)
        a.onFinal("great smiley face")
        assertThat(a.committedText).isEqualTo("Great:)")
    }

    // Moonshine-style: one partial per finished segment with the whole text so far, all of it
    // stable, then a final with the same text.

    /**
     * Feeds [segments] like the Moonshine engine does; returns the assembler and each step's edits.
     */
    private fun moonshine(
        vararg segments: String,
        before: String? = "",
        previousSession: String = "",
    ): Pair<DictationAssembler, List<List<DictationEdit>>> {
        val a = DictationAssembler(before, previousSession = previousSession)
        val steps = ArrayList<List<DictationEdit>>()
        var text = ""
        for (segment in segments) {
            text = if (text.isEmpty()) segment else "$text $segment"
            steps += a.onPartial(text, text.length, endsSegment = true)
        }
        steps += a.onFinal(text)
        return a to steps
    }

    @Test
    fun moonshineStyleSpokenCommandsEachWork() {
        val cases =
            listOf(
                "new line" to "Hello\nWorld",
                "newline" to "Hello\nWorld",
                "new paragraph" to "Hello\n\nWorld",
                "period" to "Hello. World",
                "full stop" to "Hello. World",
                "comma" to "Hello, world",
                "question mark" to "Hello? World",
                "exclamation mark" to "Hello! World",
                "exclamation point" to "Hello! World",
                "colon" to "Hello: world",
                "semicolon" to "Hello; world",
            )
        for ((spoken, expected) in cases) {
            val (a, _) = moonshine("hello", spoken, "world")
            assertThat(a.committedText).isEqualTo(expected)
            // Said with the engine's own punctuation and capitals too.
            val (b, _) =
                moonshine("Hello", "${spoken.replaceFirstChar { it.uppercaseChar() }}.", "World")
            assertThat(b.committedText.replace(Regex("\\s+"), " ").lowercase())
                .isEqualTo(expected.replace(Regex("\\s+"), " ").lowercase())
        }
    }

    @Test
    fun moonshineStyleDeleteThatRemovesThePreviousSegmentAtOnce() {
        val a = DictationAssembler("")
        assertThat(a.onPartial("Hello there.", 12, endsSegment = true))
            .containsExactly(Commit("Hello there."))
        assertThat(a.onPartial("Hello there. Second thing.", 26, endsSegment = true))
            .containsExactly(Commit(" Second thing."))
        // The segment "Delete that." is the command, not text.
        assertThat(a.onPartial("Hello there. Second thing. Delete that.", 39, endsSegment = true))
            .containsExactly(DeleteLastDictation)
        assertThat(a.lastDeletedText).isEqualTo(" Second thing.")
        assertThat(a.committedText).isEqualTo("Hello there.")
        // More speech follows the removed segment; the final adds nothing.
        assertThat(
                a.onPartial(
                    "Hello there. Second thing. Delete that. Better thing.",
                    53,
                    endsSegment = true,
                )
            )
            .containsExactly(Commit(" Better thing."))
        assertThat(a.onFinal("Hello there. Second thing. Delete that. Better thing.")).isEmpty()
        assertThat(a.committedText).isEqualTo("Hello there. Better thing.")
    }

    @Test
    fun moonshineStyleDeleteThatAsTheLastSegmentAlsoSurvivesTheFinal() {
        val (a, steps) = moonshine("first thing", "delete that")
        assertThat(steps.flatten()).contains(DeleteLastDictation)
        assertThat(steps.flatten().count { it == DeleteLastDictation }).isEqualTo(1)
        assertThat(a.committedText).isEmpty()
    }

    @Test
    fun moonshineStyleDeleteThatAloneInTheSessionDeletesNothingItself() {
        val (a, steps) = moonshine("delete that")
        assertThat(steps.flatten()).isEmpty()
        assertThat(a.committedText).isEmpty()
    }

    @Test
    fun moonshineStyleDeleteThatWithinASentenceIsText() {
        val (a, _) = moonshine("please", "delete that file")
        assertThat(a.committedText).isEqualTo("Please delete that file")
    }

    @Test
    fun aStreamingEngineHoldsWholeDeleteThatUntilTheFinal() {
        val a = DictationAssembler("")
        a.onFinal("hello")
        // Stable, but it could still grow into "delete that file": not committed.
        assertThat(a.onPartial("delete that", 11)).containsExactly(Composing(" delete that"))
        assertThat(a.committedText).isEqualTo("Hello")
        assertThat(a.onFinal("delete that")).containsExactly(ClearComposing, DeleteLastDictation)
        assertThat(a.committedText).isEmpty()
        // ... and when it does grow, it is text.
        val b = DictationAssembler("")
        b.onPartial("delete that", 11)
        b.onPartial("delete that file", 16)
        b.onFinal("delete that file")
        assertThat(b.committedText).isEqualTo("Delete that file")
    }

    @Test
    fun deleteThatRemovesThePreviousSessionsTextWhenThisSessionHasNone() {
        val a = DictationAssembler("Hi. Hello there", previousSession = " Hello there")
        assertThat(a.onFinal("Delete that.")).containsExactly(DeleteLastDictation)
        assertThat(a.lastDeletedText).isEqualTo(" Hello there")
        assertThat(a.carryOverText).isEmpty()
        // It is spent: a second one deletes nothing, and the next words follow "Hi."
        assertThat(a.onFinal("delete that")).isEmpty()
        a.onFinal("again")
        assertThat(a.committedText).isEqualTo(" Again")
        // This session's own text goes first.
        val b = DictationAssembler("Hi. Hello there", previousSession = " Hello there")
        b.onFinal("new thing")
        assertThat(b.onFinal("delete that")).containsExactly(DeleteLastDictation)
        assertThat(b.lastDeletedText).isEqualTo(" new thing")
        assertThat(b.carryOverText).isEqualTo(" Hello there")
    }
}

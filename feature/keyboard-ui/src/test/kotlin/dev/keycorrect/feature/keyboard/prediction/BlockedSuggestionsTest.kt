package dev.keycorrect.feature.keyboard.prediction

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.PredictResult
import org.junit.Test

class BlockedSuggestionsTest {
    private fun c(text: String) = Candidate(text, -1f, CandidateSource.Dictionary)

    private fun result(
        vararg words: String,
        autocorrect: String? = null,
        phrase: String? = null,
        runnerUp: String? = null,
    ) =
        PredictResult(
            candidates = words.map(::c),
            literal = null,
            autocorrect = autocorrect?.let(::c),
            phrase = phrase?.let(::c),
            autocorrectRunnerUp = runnerUp?.let(::c),
        )

    @Test
    fun nothingBlockedReturnsTheSameResult() {
        val r = result("a", "b")
        assertThat(BlockedSuggestions().filter(r)).isSameInstanceAs(r)
    }

    @Test
    fun aResultWithNoBlockedWordIsReturnedUnchanged() {
        val blocked = BlockedSuggestions().apply { add("zzz") }
        val r = result("a", "b", autocorrect = "c")
        assertThat(blocked.filter(r)).isSameInstanceAs(r)
    }

    @Test
    fun blockedWordsAreRemovedFromEverySlotCaseInsensitively() {
        val blocked = BlockedSuggestions().apply { add("Bad") }
        val out =
            blocked.filter(
                result("ok", "BAD", autocorrect = "bad", phrase = "Bad", runnerUp = "bad")
            )
        assertThat(out.candidates.map { it.text }).containsExactly("ok")
        assertThat(out.autocorrect).isNull()
        assertThat(out.phrase).isNull()
        assertThat(out.autocorrectRunnerUp).isNull()
    }

    @Test
    fun clearForgetsEverything() {
        val blocked = BlockedSuggestions().apply { add("bad") }
        blocked.clear()
        val r = result("bad")
        assertThat(blocked.filter(r)).isSameInstanceAs(r)
    }

    @Test
    fun onlyTheNewest32AreRemembered() {
        val blocked = BlockedSuggestions()
        for (i in 0 until 33) blocked.add("w$i")
        val out = blocked.filter(result("w0", "w1", "w32"))
        assertThat(out.candidates.map { it.text }).containsExactly("w0")
    }
}

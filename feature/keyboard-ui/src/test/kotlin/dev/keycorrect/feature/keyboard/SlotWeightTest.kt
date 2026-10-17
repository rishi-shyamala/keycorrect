package dev.keycorrect.feature.keyboard

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.feature.keyboard.prediction.SlotKind
import dev.keycorrect.feature.keyboard.prediction.StripSlot
import org.junit.Test

/** How the prediction strip shares its width between the three slots. */
class SlotWeightTest {
    private fun word(text: String) = StripSlot(text, SlotKind.Suggestion)

    @Test
    fun longerWordsGetMoreRoomWithAMinimum() {
        assertThat(slotWeight(word("a"))).isEqualTo(slotWeight(null))
        assertThat(slotWeight(word("everything"))).isGreaterThan(slotWeight(word("the")))
    }

    @Test
    fun aVeryLongPhraseCannotTakeEverything() {
        val phrase = StripSlot("on my way to the airport right now", SlotKind.Phrase)
        assertThat(slotWeight(phrase)).isAtMost(14f)
        assertThat(slotWeight(phrase)).isLessThan(3 * slotWeight(word("the")))
    }
}

package dev.keycorrect.feature.keyboard.emoji

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmojiTabIconsTest {
    private val groups =
        listOf(
            "Smileys & Emotion",
            "People & Body",
            "Animals & Nature",
            "Food & Drink",
            "Travel & Places",
            "Activities",
            "Objects",
            "Symbols",
            "Flags",
        )

    @Test
    fun everyKnownGroupAndRecentsHasAnOutlineIcon() {
        for (name in groups + EmojiPanelState.RECENTS) assertThat(tabIconFor(name)).isNotNull()
    }

    @Test
    fun iconsAreDistinctAndHaveStrokedPaths() {
        val icons = (groups + EmojiPanelState.RECENTS).map { tabIconFor(it)!! }
        assertThat(icons.map { it.name }.toSet()).hasSize(icons.size)
        assertThat(icons.all { it.root.size > 0 }).isTrue()
    }

    @Test
    fun unknownGroupAndKaomojiHaveNoIcon() {
        assertThat(tabIconFor("Something new")).isNull()
        assertThat(tabIconFor(EmojiPanelState.KAOMOJI)).isNull()
    }
}

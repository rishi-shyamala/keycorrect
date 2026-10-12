package dev.keycorrect.ime

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.data.InMemoryEmojiHistoryStore
import dev.keycorrect.core.emoji.Emoji
import dev.keycorrect.core.emoji.EmojiCatalog
import dev.keycorrect.core.emoji.EmojiGroup
import dev.keycorrect.core.emoji.EmojiVariant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppEmojiEnvironmentTest {
    private val pizza = "🍕"
    private val thumbs = "👍"
    private val thumbsLight = "👍🏻"
    private val rare = "🫨"
    private val catalog =
        EmojiCatalog(
            "17.0",
            "48.2",
            listOf(
                EmojiGroup(
                    "Test",
                    listOf(
                        Emoji(pizza, "pizza", listOf("food"), emptyList(), "Test"),
                        Emoji(
                            thumbs,
                            "thumbs up",
                            emptyList(),
                            listOf(EmojiVariant(thumbsLight, "light skin tone")),
                            "Test",
                        ),
                        Emoji(rare, "shaking face", emptyList(), emptyList(), "Test"),
                    ),
                )
            ),
            emptyList(),
        )
    private val store = InMemoryEmojiHistoryStore()
    private var recording = true
    private var loads = 0
    private val scope = TestScope(UnconfinedTestDispatcher())

    private fun env(hasGlyph: (String) -> Boolean = { it != rare }) =
        AppEmojiEnvironment(
            scope,
            { store },
            {
                loads++
                catalog
            },
            hasGlyph,
            { recording },
            UnconfinedTestDispatcher(scope.testScheduler),
        )

    @Test
    fun theCatalogIsNullUntilStartedThenLoadedOnceWithoutUndrawableEmoji() = runTest {
        val env = env()
        assertThat(env.catalog).isNull()
        env.start()
        env.start()
        assertThat(loads).isEqualTo(1)
        assertThat(env.catalog!!.groups.single().emoji.map { it.value })
            .containsExactly(pizza, thumbs)
    }

    @Test
    fun savedRecentsAndChoicesAreLoadedIntoMemory() = runTest {
        store.recordUse(pizza, pizza)
        store.setPreferredVariant(thumbs, thumbsLight)
        val env = env()
        env.start()
        assertThat(env.recents).containsExactly(pizza)
        assertThat(env.preferredVariant(thumbs)).isEqualTo(thumbsLight)
    }

    @Test
    fun picksAndChoicesUpdateMemoryAtOnceAndAreSaved() = runTest {
        val env = env()
        env.start()
        env.onEmojiUsed(thumbsLight, thumbs)
        env.onEmojiUsed(pizza, pizza)
        env.onEmojiUsed(thumbsLight, thumbs)
        env.setPreferredVariant(thumbs, thumbsLight)
        assertThat(env.recents).containsExactly(thumbsLight, pizza).inOrder()
        assertThat(env.preferredVariant(thumbs)).isEqualTo(thumbsLight)
        assertThat(store.recents(10)).containsExactly(thumbsLight, pizza).inOrder()
        assertThat(store.preferredVariant(thumbs)).isEqualTo(thumbsLight)
    }

    @Test
    fun nothingIsRememberedWhileNotRecording() = runTest {
        val env = env()
        env.start()
        recording = false
        env.onEmojiUsed(pizza, pizza)
        env.setPreferredVariant(thumbs, thumbsLight)
        assertThat(env.recents).isEmpty()
        assertThat(env.preferredVariant(thumbs)).isNull()
        assertThat(store.recents(10)).isEmpty()
    }

    @Test
    fun aFailingCatalogLoadLeavesTheCatalogNull() = runTest {
        val env =
            AppEmojiEnvironment(
                scope,
                { store },
                { error("corrupt") },
                { true },
                { true },
                UnconfinedTestDispatcher(scope.testScheduler),
            )
        env.start()
        assertThat(env.catalog).isNull()
    }

    @Test
    fun aResetInTheSettingsEmptiesMemoryAtOnce() = runTest {
        val env = env()
        env.start()
        env.onEmojiUsed(pizza, pizza)
        env.setPreferredVariant(thumbs, thumbsLight)
        store.clear() // what "Reset all learning" / "Erase personal data" did to the store
        env.reloadHistory()
        assertThat(env.recents).isEmpty()
        assertThat(env.preferredVariant(thumbs)).isNull()
    }
}

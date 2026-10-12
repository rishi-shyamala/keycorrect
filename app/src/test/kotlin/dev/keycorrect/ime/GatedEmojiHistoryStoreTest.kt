package dev.keycorrect.ime

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.data.InMemoryEmojiHistoryStore
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GatedEmojiHistoryStoreTest {
    private var recording = true
    private val inner = InMemoryEmojiHistoryStore()
    private val gated = GatedEmojiHistoryStore(inner) { recording }

    @Test
    fun recordsWhileAllowed() = runTest {
        gated.recordUse("😀", "😀")
        gated.setPreferredVariant("👍", "👍🏽")
        assertThat(gated.recents(5)).containsExactly("😀")
        assertThat(gated.preferredVariant("👍")).isEqualTo("👍🏽")
    }

    @Test
    fun recordsNothingWhenIncognitoOrLearningOff() = runTest {
        recording = false
        gated.recordUse("😀", "😀")
        gated.setPreferredVariant("👍", "👍🏽")
        assertThat(inner.recents(5)).isEmpty()
        assertThat(inner.preferredVariant("👍")).isNull()
    }

    @Test
    fun clearingAndReadingStillWorkWhileNotRecording() = runTest {
        gated.recordUse("😀", "😀")
        gated.setPreferredVariant("👍", "👍🏽")
        recording = false
        assertThat(gated.recents(5)).containsExactly("😀")
        gated.setPreferredVariant("👍", null)
        assertThat(inner.preferredVariant("👍")).isNull()
        gated.clear()
        assertThat(inner.recents(5)).isEmpty()
    }
}

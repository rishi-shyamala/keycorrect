package dev.keycorrect.core.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class InMemoryStoresTest {
    private var now = 1_000L
    private val clock: () -> Long = { now }

    @Test
    fun userWordCrud() = runTest {
        val store = InMemoryUserDictionaryStore(clock)
        val added = store.add("  Rishi ", 200, " en-GB ")
        assertThat(added).isEqualTo(UserWord("Rishi", 200, "en-GB", 1000, 1000))
        assertThat(store.contains("Rishi")).isTrue()
        assertThat(store.contains("rishi")).isFalse()

        now = 2_000
        assertThat(store.update("Rishi", 50, null)).isTrue()
        assertThat(store.snapshot["Rishi"]).isEqualTo(UserWord("Rishi", 50, null, 1000, 2000))
        assertThat(store.update("missing", 50)).isFalse()

        assertThat(store.remove("Rishi")).isTrue()
        assertThat(store.remove("Rishi")).isFalse()
        assertThat(store.snapshot.size).isEqualTo(0)
    }

    @Test
    fun addingExistingWordKeepsCreatedAt() = runTest {
        val store = InMemoryUserDictionaryStore(clock)
        store.add("foo")
        now = 5_000
        val again = store.add("foo", 10)
        assertThat(again.createdAt).isEqualTo(1000)
        assertThat(again.updatedAt).isEqualTo(5000)
        assertThat(again.frequency).isEqualTo(10)
    }

    @Test
    fun invalidWordsThrow() = runTest {
        val store = InMemoryUserDictionaryStore(clock)
        val e =
            assertThrows(InvalidEntryException::class.java) {
                kotlinx.coroutines.runBlocking { store.add("a b") }
            }
        assertThat(e.error).isEqualTo(ValidationError.WORD_CONTAINS_WHITESPACE)
        assertThat(store.snapshot.size).isEqualTo(0)
    }

    @Test
    fun allEmitsSortedListAndUpdates() =
        runTest(UnconfinedTestDispatcher()) {
            val store = InMemoryUserDictionaryStore(clock)
            store.add("zeta")
            val emissions = mutableListOf<List<String>>()
            val job = launch {
                store.all().take(3).toList().forEach { emissions += it.map { w -> w.word } }
            }
            store.add("alpha")
            store.remove("zeta")
            job.join()
            assertThat(emissions)
                .containsExactly(listOf("zeta"), listOf("alpha", "zeta"), listOf("alpha"))
                .inOrder()
        }

    @Test
    fun shortcutTriggerIsNormalisedEverywhere() = runTest {
        val store = InMemoryShortcutStore(clock)
        val stored = store.put("  OMW ", "On my way! {cursor}", ShortcutMode.AUTO_EXPAND)
        assertThat(stored.trigger).isEqualTo("omw")
        assertThat(stored.expansion).isEqualTo("On my way! {cursor}")
        assertThat(store.contains("OMW")).isTrue()
        assertThat(store.get(" Omw")?.mode).isEqualTo(ShortcutMode.AUTO_EXPAND)
        assertThat(store.snapshot["OMW"]).isEqualTo(stored)
        assertThat(store.remove("omw ")).isTrue()
        assertThat(store.get("omw")).isNull()
    }

    @Test
    fun shortcutPutReplacesAndKeepsCreatedAt() = runTest {
        val store = InMemoryShortcutStore(clock)
        store.put("sig", "a")
        now = 9_000
        val again = store.put("SIG", "b", ShortcutMode.AUTO_EXPAND)
        assertThat(again).isEqualTo(Shortcut("sig", "b", ShortcutMode.AUTO_EXPAND, 1000, 9000))
        assertThat(store.all().first()).hasSize(1)
    }

    @Test
    fun invalidShortcutsThrow() = runTest {
        val store = InMemoryShortcutStore(clock)
        val e =
            assertThrows(InvalidEntryException::class.java) {
                kotlinx.coroutines.runBlocking { store.put("a", "x".repeat(1001)) }
            }
        assertThat(e.error).isEqualTo(ValidationError.EXPANSION_TOO_LONG)
    }
}

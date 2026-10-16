package dev.keycorrect.core.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InMemoryClipboardStoreTest {
    private var now = 1_000L
    private val clock: () -> Long = { now }

    private suspend fun InMemoryClipboardStore.texts() = clips().map { it.text }

    @Test
    fun clipsAreNewestFirstEvenWithinOneMillisecond() = runTest {
        val store = InMemoryClipboardStore(clock)
        store.insert(NewClip.text("a"))
        store.insert(NewClip.text("b"))
        store.insert(NewClip.text("c"))
        assertThat(store.texts()).containsExactly("c", "b", "a").inOrder()
    }

    @Test
    fun equalTextAsNewestOnlyBumpsIt() = runTest {
        val store = InMemoryClipboardStore(clock)
        val first = store.insert(NewClip.text("a"))
        now = 2_000
        val again = store.insert(NewClip.text("a"))
        assertThat(again).isEqualTo(first)
        val only = store.clips().single()
        assertThat(only.createdAt).isEqualTo(2_000)
    }

    @Test
    fun equalTextAnywhereInTheHistoryMovesToTheTop() = runTest {
        val store = InMemoryClipboardStore(clock)
        val a = store.insert(NewClip.text("a"))
        store.insert(NewClip.text("b"))
        store.insert(NewClip.text("c"))
        now = 5_000
        assertThat(store.insert(NewClip.text("a"))).isEqualTo(a)
        assertThat(store.texts()).containsExactly("a", "c", "b").inOrder()
        assertThat(store.clips().first().createdAt).isEqualTo(5_000)
    }

    @Test
    fun imageClipFindsOneImageByBlobName() = runTest {
        val store = InMemoryClipboardStore(clock)
        store.insert(NewClip.text("f1"))
        val id = store.insert(NewClip.image("f1", "image/png", 10))
        store.insert(NewClip.image("f2", "image/jpeg", 20))
        assertThat(store.imageClip("f1")?.id).isEqualTo(id)
        assertThat(store.imageClip("f2")?.mimeType).isEqualTo("image/jpeg")
        assertThat(store.imageClip("missing")).isNull()
    }

    @Test
    fun imagesAreNeverDeduplicated() = runTest {
        val store = InMemoryClipboardStore(clock)
        val a = store.insert(NewClip.image("f1", "image/png", 10))
        val b = store.insert(NewClip.image("f1", "image/png", 10))
        assertThat(a).isNotEqualTo(b)
        assertThat(store.clips().map { it.kind }).containsExactly(ClipKind.Image, ClipKind.Image)
    }

    @Test
    fun pinDeleteAndClear() = runTest {
        val store = InMemoryClipboardStore(clock)
        val a = store.insert(NewClip.text("a"))
        val b = store.insert(NewClip.text("b"))
        store.insert(NewClip.text("c"))
        store.setPinned(a, true)
        assertThat(store.clips().single { it.id == a }.pinned).isTrue()
        store.delete(listOf(b))
        assertThat(store.texts()).containsExactly("c", "a").inOrder()
        store.clear(keepPinned = true)
        assertThat(store.texts()).containsExactly("a")
        store.clear(keepPinned = false)
        assertThat(store.clips()).isEmpty()
    }

    @Test
    fun clearSinceRemovesNewerClipsIncludingPinned() = runTest {
        val store = InMemoryClipboardStore(clock)
        now = 1_000
        store.insert(NewClip.text("old"))
        now = 5_000
        val pinned = store.insert(NewClip.text("new", pinned = true))
        store.insert(NewClip.text("newer"))
        assertThat(pinned).isGreaterThan(0)
        store.clearSince(3_000)
        assertThat(store.texts()).containsExactly("old")
    }

    @Test
    fun updateTextChangesOnlyTextClips() = runTest {
        val store = InMemoryClipboardStore(clock)
        val t = store.insert(NewClip.text("https://x.test/?utm_source=a"))
        val i = store.insert(NewClip.image("f", "image/png", 3))
        store.updateText(t, "https://x.test/")
        store.updateText(i, "nope")
        assertThat(store.clips().single { it.id == t }.text).isEqualTo("https://x.test/")
        assertThat(store.clips().single { it.id == i }.text).isNull()
    }

    @Test
    fun newClipValidatesItsShape() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { NewClip.text("") }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            NewClip.image("", "image/png", 1)
        }
    }
}

package dev.keycorrect.core.layout

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertThrows
import org.junit.Test

class LayoutRepositoryTest {
    private fun json(id: String) = """{ "format": 1, "id": "$id", "name": "N", "rows": [["a"]] }"""

    @Test
    fun cachesByIdAndReturnsSameInstance() {
        val loads = AtomicInteger()
        val repo = LayoutRepository { id ->
            loads.incrementAndGet()
            json(id)
        }
        val first = repo.get("one")
        assertThat(repo.get("one")).isSameInstanceAs(first)
        assertThat(repo.get("two")).isNotSameInstanceAs(first)
        assertThat(loads.get()).isEqualTo(2)
    }

    @Test
    fun errorsAreNotCached() {
        var text: String? = null
        val repo = LayoutRepository { text }
        assertThrows(LayoutParseException::class.java) { repo.get("one") }
        text = json("one")
        assertThat(repo.get("one").id).isEqualTo("one")
    }

    @Test
    fun fileIdMustMatchRequestedId() {
        val repo = LayoutRepository { json("other") }
        val msg = assertThrows(LayoutParseException::class.java) { repo.get("one") }.message
        assertThat(msg).contains("other")
        assertThat(msg).contains("one")
    }

    @Test
    fun concurrentAccessYieldsOneInstance() {
        val repo = LayoutRepository { id -> json(id) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..32).map { pool.submit<Layout> { repo.get("one") } }.map { it.get() }
            assertThat(results.toSet()).hasSize(1)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun composeResultsAreCached() {
        val composer = KeyboardComposer(BuiltInLayouts.repository())
        val a = composer.compose(KeyboardSet.Default, "letters")
        assertThat(composer.compose(KeyboardSet.Default, "letters")).isSameInstanceAs(a)
        assertThat(composer.compose(KeyboardSet.Default, "letters", BottomRowVariant.Url))
            .isNotSameInstanceAs(a)
        assertThat(composer.compose(KeyboardSet.Default, "letters", options = ComposeOptions(true)))
            .isNotSameInstanceAs(a)
    }
}

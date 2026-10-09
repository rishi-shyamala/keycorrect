package dev.keycorrect.feature.keyboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InlineAutofillOrderingTest {
    private fun order(vararg items: String) = orderPinnedLast(items.toList()) { it.startsWith("*") }

    @Test
    fun pinnedItemsMoveToTheEnd() {
        assertThat(order("*pm", "a", "b")).containsExactly("a", "b", "*pm").inOrder()
    }

    @Test
    fun orderIsStableWithinGroups() {
        assertThat(order("c", "*y", "a", "*x", "b"))
            .containsExactly("c", "a", "b", "*y", "*x")
            .inOrder()
    }

    @Test
    fun noPinnedItemsKeepsOrder() {
        assertThat(order("b", "a")).containsExactly("b", "a").inOrder()
    }

    @Test
    fun emptyStaysEmpty() {
        assertThat(order()).isEmpty()
    }
}

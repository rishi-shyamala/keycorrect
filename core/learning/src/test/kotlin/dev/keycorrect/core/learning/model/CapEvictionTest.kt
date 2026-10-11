package dev.keycorrect.core.learning.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Over the cap, equally weak entries are evicted in key order, not in hash-map order. */
class CapEvictionTest {
    @Test
    fun tiedCorrectionsAreEvictedInKeyOrder() {
        val memory = CorrectionMemory(PersonalModelConfig(maxCorrectionEntries = 10), Decay(1e9))
        val typed = ('a'..'l').map { "${it}xx" }.shuffled(java.util.Random(3))
        for (t in typed) memory.add(t, "word", "", 1.0, 0L)
        memory.enforceCaps(0L)
        // 12 entries, cap 10: down to 9, and the three smallest keys go.
        assertThat(memory.exportRows().map { it.typed })
            .containsExactlyElementsIn(('d'..'l').map { "${it}xx" })
    }
}

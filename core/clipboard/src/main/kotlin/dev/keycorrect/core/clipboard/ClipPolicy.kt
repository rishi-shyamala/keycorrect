package dev.keycorrect.core.clipboard

/** What retention needs to know about a stored clip. [imageBytes] is 0 for text clips. */
data class ClipMeta(
    val id: Long,
    val createdAt: Long,
    val pinned: Boolean,
    val imageBytes: Long,
)

/**
 * Retention. Pure and deterministic.
 *
 * Choices:
 * - Pinned clips are never deleted, whatever their age or size, and do not count toward [maxItems].
 * - Pinned images do count toward [imageBudgetBytes], because they occupy the same disk space.
 * - Ties in age are broken by id (higher id = newer).
 */
object ClipPolicy {
    const val DEFAULT_MAX_ITEMS = 30
    const val DEFAULT_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    const val DEFAULT_IMAGE_BUDGET_BYTES = 20L * 1024 * 1024

    /** Ids to delete, ascending. */
    fun idsToDelete(
        clips: List<ClipMeta>,
        now: Long,
        maxItems: Int = DEFAULT_MAX_ITEMS,
        ttlMillis: Long = DEFAULT_TTL_MILLIS,
        imageBudgetBytes: Long = DEFAULT_IMAGE_BUDGET_BYTES,
    ): List<Long> {
        val doomed = HashSet<Long>()
        val (expired, live) =
            clips.filter { !it.pinned }.partition { now - it.createdAt > ttlMillis }
        expired.mapTo(doomed) { it.id }

        // Newest first; everything past the cap is the oldest.
        val survivors = live.sortedWith(NEWEST_FIRST)
        survivors.drop(maxItems.coerceAtLeast(0)).mapTo(doomed) { it.id }

        val keptUnpinned = survivors.filter { it.id !in doomed }
        val pinnedBytes = clips.filter { it.pinned }.sumOf { it.imageBytes }
        var total = pinnedBytes + keptUnpinned.sumOf { it.imageBytes }
        for (clip in keptUnpinned.asReversed()) {
            if (total <= imageBudgetBytes) break
            if (clip.imageBytes > 0) {
                doomed += clip.id
                total -= clip.imageBytes
            }
        }
        return doomed.sorted()
    }

    private val NEWEST_FIRST =
        compareByDescending<ClipMeta> { it.createdAt }.thenByDescending { it.id }
}

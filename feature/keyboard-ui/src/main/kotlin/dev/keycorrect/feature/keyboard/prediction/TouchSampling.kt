package dev.keycorrect.feature.keyboard.prediction

import dev.keycorrect.core.engineapi.KeySpec
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.learning.TouchSample

/** A touch farther than this many key sizes from the intended key is not plausibly aimed at it. */
private const val MAX_TOUCH_OFFSET = 0.7f

/** Most positions of a word where the intended char differs from the one registered. */
private const val MAX_MISMATCHED_POSITIONS = 1

/**
 * One sample per typed char that came from a tap, measured from the centre of the key of the char
 * the user *meant*: the same position of [committed] (case-insensitively). Same-length words align
 * position by position (autocorrections, substitutions); otherwise only the common prefix and
 * suffix do (completions, insertions, deletions). A touch farther than [MAX_TOUCH_OFFSET] key sizes
 * from that key isn't plausibly aimed at it and is dropped. A word with more than
 * [MAX_MISMATCHED_POSITIONS] mismatched positions yields no samples.
 */
internal fun touchSamples(
    typed: String,
    committed: String,
    touchList: List<TouchPoint?>,
    keyByChar: Map<Char, KeySpec>,
): List<TouchSample?> {
    if (typed.isEmpty()) return emptyList()
    val n = typed.length
    val m = committed.length
    var prefix = 0
    var suffix = 0
    if (n != m) {
        val limit = minOf(n, m)
        while (
            prefix < limit && typed[prefix].lowercaseChar() == committed[prefix].lowercaseChar()
        ) {
            prefix++
        }
        while (
            suffix < limit - prefix &&
                typed[n - 1 - suffix].lowercaseChar() == committed[m - 1 - suffix].lowercaseChar()
        ) {
            suffix++
        }
    }
    fun aligned(i: Int): Int =
        when {
            n == m -> i
            i < prefix -> i
            i >= n - suffix -> m - (n - i)
            else -> -1
        }
    // A word with more than one mismatched position (intended != registered) is too unclear
    // to say which touch was aimed where: no samples at all.
    var mismatches = 0
    for (i in 0 until n) {
        val j = aligned(i)
        if (j < 0 || typed[i].lowercaseChar() != committed[j].lowercaseChar()) mismatches++
    }
    if (mismatches > MAX_MISMATCHED_POSITIONS) return List(n) { null }
    return List(n) { i ->
        val touch = touchList.getOrNull(i) ?: return@List null
        val j = aligned(i)
        if (j < 0) return@List null
        val intended = committed[j].lowercaseChar()
        val key = keyByChar[intended] ?: return@List null
        if (key.width <= 0f || key.height <= 0f) return@List null
        val dx = (touch.x - key.centerX) / key.width
        val dy = (touch.y - key.centerY) / key.height
        if (kotlin.math.abs(dx) > MAX_TOUCH_OFFSET || kotlin.math.abs(dy) > MAX_TOUCH_OFFSET) {
            null
        } else {
            TouchSample(intended, dx, dy)
        }
    }
}

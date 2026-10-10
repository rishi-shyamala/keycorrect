package dev.keycorrect.feature.keyboard.prediction

import dev.keycorrect.core.learning.CommitMethod as Learned
import dev.keycorrect.core.learning.RevertKind
import kotlin.math.ceil

/**
 * A word the user finished recently, remembered so that a later edit of it can be recognised
 * (docs/PLAN.md §6.2: "post-commit edits observed within a tracking window"). It holds no position:
 * the text keeps shifting, so a word is found again by *what it says* and *what precedes it*, both
 * checked against the editor's text when the word is re-composed.
 */
internal class Tracked(
    /** What the user typed (the literal). Differs from [committed] only for an autocorrection. */
    val typed: String,
    val committed: String,
    /** The last ≤ 2 words of the sentence before the word. */
    val context: List<String>,
    val method: Learned,
    /** Uptime when it was committed. */
    val at: Long,
    /** The tie runner-up when the autocorrection chose between near-tied candidates. */
    val runnerUp: String? = null,
) {
    /**
     * Set while this autocorrection was taken back (one backspace, or deleted and retyped) and the
     * restored word has not been finished yet: the revert is held back until it is known whether
     * the user keeps [typed] (reported as this kind), edits it into something else or picks another
     * suggestion. The text says [typed], not [committed], for as long as this is set.
     */
    var undone: RevertKind? = null

    /** Position in the order words were finished; set by [WordMemory.add]. */
    var serial = 0L
        internal set

    val autocorrected: Boolean
        get() = method == Learned.Autocorrected

    /** The word was inserted by tapping a suggestion (so deleting it right away is a mis-tap). */
    val picked: Boolean
        get() = method == Learned.SuggestionPicked || method == Learned.PredictionPicked
}

/**
 * The most recent words, for [windowWords] more words or [windowMillis], whichever ends first. Not
 * kept at all while the privacy gate is closed (the owner clears it). Main thread only.
 */
internal class WordMemory(
    private val capacity: Int = 8,
    private val windowWords: Int = 5,
    private val windowMillis: Long = 120_000L,
) {
    private val words = ArrayList<Tracked>(capacity)
    private var counter = 0L

    fun add(t: Tracked) {
        t.serial = ++counter
        if (words.size == capacity) words.removeAt(0)
        words.add(t)
    }

    /**
     * The newest tracked word that says [word] with [context] before it. [word] may also be the
     * tracked word minus its last character or two: the user backspaced into a word that has no
     * separator after it, and the editor re-composed what was left of it. An exact word wins over a
     * shortened one.
     */
    fun find(word: String, context: List<String>, now: Long): Tracked? {
        prune(now)
        val sameContext = words.asReversed().filter { it.context == context }
        return sameContext.firstOrNull { shownText(it) == word }
            ?: sameContext.firstOrNull { shortened(shownText(it), word) }
    }

    /** True while [t] is still inside the tracking window. */
    fun isFresh(t: Tracked, now: Long): Boolean =
        counter - t.serial < windowWords && now - t.at <= windowMillis

    /** What the editor currently says for [t]: the typed word once its correction was undone. */
    private fun shownText(t: Tracked): String = if (t.undone != null) t.typed else t.committed

    fun remove(t: Tracked) {
        words.remove(t)
    }

    fun clear() {
        words.clear()
    }

    val size: Int
        get() = words.size

    private fun prune(now: Long) {
        words.removeAll { counter - it.serial >= windowWords || now - it.at > windowMillis }
    }

    private fun shortened(full: String, part: String): Boolean =
        part.isNotEmpty() && full.length - part.length in 1..2 && full.startsWith(part)
}

/**
 * A word being edited: the user backspaced into the word [original] and it was re-composed.
 * [tracked] is the remembered word it matches (null for an older one nobody remembers). [deleted]
 * turns true once the whole word was deleted: from then on it is a replacement, not a touch-up.
 */
internal class EditSession(val original: String, var tracked: Tracked?) {
    var deleted = false
}

/**
 * A phrase chip's fate (docs/PLAN.md §6.3 rows 18–20).
 * - Offered but not tapped ([taken] false): the first word was typed; [index] words of [words]
 *   matched so far, the next word typed decides.
 * - Taken: the whole phrase was inserted; it is judged at the next word, or when the field is left.
 */
internal class PhraseWatch(
    val words: List<String>,
    val context: List<String>,
    val taken: Boolean,
    var index: Int,
)

/** Splits a phrase into words, dropping edge punctuation. */
internal fun phraseWords(text: String): List<String> =
    text
        .split(' ', '\n', '\t')
        .map { it.trim { c -> !c.isLetterOrDigit() } }
        .filter { it.isNotEmpty() }

internal fun sameWord(a: String, b: String): Boolean = fold(a) == fold(b)

internal fun fold(s: String): String = s.lowercase().replace('’', '\'').replace('‘', '\'')

/**
 * Whether [x], what the user turned an autocorrected word into, is plausibly a correction of the
 * same word (close to what they typed [t] or to the correction) rather than a rewording. The
 * classifier takes an edit at face value, so unrelated rewordings are filtered out here.
 */
internal fun relatedEdit(typed: String, corrected: String, x: String): Boolean {
    val a = fold(typed)
    val limit = maxOf(1, ceil(a.length / 3.0).toInt())
    val b = fold(x)
    return minOf(editDistance(a, b), editDistance(fold(corrected), b)) <= limit
}

/** Optimal-string-alignment distance (Levenshtein plus adjacent transposition = 1). */
internal fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var best = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                best = minOf(best, d[i - 2][j - 2] + 1)
            }
            d[i][j] = best
        }
    }
    return d[a.length][b.length]
}

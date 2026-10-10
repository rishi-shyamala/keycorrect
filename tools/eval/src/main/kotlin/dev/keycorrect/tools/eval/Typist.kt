package dev.keycorrect.tools.eval

import dev.keycorrect.core.engineapi.ComposingInput
import dev.keycorrect.core.engineapi.TouchPoint
import java.util.Random

/** Knobs of the simulated typist (docs/EVAL.md). All rates are per word. */
data class TypistConfig(
    /** Touch standard deviation as a fraction of the key width. */
    val sigmaX: Float = 0.35f,
    /** Touch standard deviation as a fraction of the key height. */
    val sigmaY: Float = 0.35f,
    /** A letter is skipped. */
    val omissionRate: Float = 0.01f,
    /** An extra tap on a neighbouring key is inserted next to a letter. */
    val insertionRate: Float = 0.01f,
    /** Two adjacent letters are typed in the wrong order. */
    val transpositionRate: Float = 0.01f,
    /** Milliseconds between taps (touch time stamps). */
    val tapIntervalMs: Long = 150L,
    /**
     * Systematic touch offset added to every tap, in key widths (x, positive = right) and key
     * heights (y, positive = down): the "thumb bias" a personal user has (learn mode).
     */
    val biasX: Float = 0f,
    val biasY: Float = 0f,
)

/** The cognitive slip applied to a word, if any. */
enum class TypoKind {
    None,
    Omission,
    Insertion,
    Transposition,
}

/**
 * What the user typed for one target word.
 *
 * @property input the composing input the keyboard would hold (chars are the keys the touches
 *   landed on, so a sloppy tap shows up as a different letter).
 * @property typo the cognitive slip that was applied, [TypoKind.None] for none.
 * @property touchSlips number of taps that landed on a different key than intended.
 */
class TypedWord(val input: ComposingInput, val typo: TypoKind, val touchSlips: Int) {
    val text: String
        get() = input.text
}

/**
 * Generates touches for target words: a 2D Gaussian around each key centre plus a cognitive-typo
 * model. Deterministic given the [Random] passed to [type]; the caller seeds one per word so the
 * noise does not depend on evaluation order or on what the engine does.
 *
 * Chars that have no key on the letters layer (apostrophe, hyphen, accented letters) are typed as
 * null-touch chars (long-press popup), exactly as given. Words containing digits are not typed by
 * this class; callers skip them ([canType]).
 */
class Typist(private val keyboard: EvalKeyboard, val config: TypistConfig = TypistConfig()) {
    /** True unless [word] has a digit or is empty. */
    fun canType(word: String): Boolean = word.isNotEmpty() && word.none { it.isDigit() }

    fun type(word: String, rng: Random): TypedWord {
        require(canType(word)) { "cannot type '$word'" }
        // Plan: list of intended chars (after the cognitive slip), then one tap each.
        val slots = ArrayList<Char>(word.length + 1)
        for (c in word) slots.add(c)
        var typo = TypoKind.None
        val roll = rng.nextFloat()
        val omission = config.omissionRate
        val insertion = omission + config.insertionRate
        val transposition = insertion + config.transpositionRate
        when {
            roll < omission -> {
                val i = pickLetter(word, rng)
                if (i >= 0 && word.length >= 2) {
                    slots.removeAt(i)
                    typo = TypoKind.Omission
                }
            }
            roll < insertion -> {
                val i = pickLetter(word, rng)
                val extra = if (i >= 0) pickNeighbour(word[i], rng) else null
                if (extra != null) {
                    // The stray tap lands before or after the intended letter.
                    val at = if (rng.nextBoolean()) i else i + 1
                    val ch =
                        if (word.all { !it.isLetter() || it.isUpperCase() }) {
                            extra.uppercaseChar()
                        } else {
                            extra
                        }
                    slots.add(at, ch)
                    typo = TypoKind.Insertion
                }
            }
            roll < transposition -> {
                val i = pickSwap(word, rng)
                if (i >= 0) {
                    val a = slots[i]
                    slots[i] = slots[i + 1]
                    slots[i + 1] = a
                    typo = TypoKind.Transposition
                }
            }
        }
        val text = StringBuilder(slots.size)
        val touches = ArrayList<TouchPoint?>(slots.size)
        var slips = 0
        slots.forEachIndexed { index, intended ->
            val key = keyboard.key(intended)
            if (key == null) {
                text.append(intended)
                touches.add(null)
            } else {
                val x =
                    key.centerX +
                        ((rng.nextGaussian() * config.sigmaX + config.biasX) * key.width).toFloat()
                val y =
                    key.centerY +
                        ((rng.nextGaussian() * config.sigmaY + config.biasY) * key.height).toFloat()
                val hit = keyboard.charAt(x, y) ?: key.char.lowercaseChar()
                if (hit != key.char.lowercaseChar()) slips++
                // Shift state follows the intended char, as it does for a real user.
                text.append(if (intended.isUpperCase()) hit.uppercaseChar() else hit)
                touches.add(TouchPoint(x, y, index * config.tapIntervalMs))
            }
        }
        return TypedWord(ComposingInput(text.toString(), touches), typo, slips)
    }

    /** A random letter position of [word] that has a key (any slip is on a typeable letter). */
    private fun pickLetter(word: String, rng: Random): Int {
        val candidates = word.indices.filter { keyboard.hasKey(word[it]) }
        return if (candidates.isEmpty()) -1 else candidates[rng.nextInt(candidates.size)]
    }

    private fun pickNeighbour(c: Char, rng: Random): Char? {
        val n = keyboard.neighbours(c)
        return if (n.isEmpty()) null else n[rng.nextInt(n.size)].char.lowercaseChar()
    }

    /** Position i such that word[i], word[i+1] are two different keyed letters; -1 if none. */
    private fun pickSwap(word: String, rng: Random): Int {
        val candidates =
            (0 until word.length - 1).filter {
                word[it].lowercaseChar() != word[it + 1].lowercaseChar() &&
                    keyboard.hasKey(word[it]) &&
                    keyboard.hasKey(word[it + 1])
            }
        return if (candidates.isEmpty()) -1 else candidates[rng.nextInt(candidates.size)]
    }
}

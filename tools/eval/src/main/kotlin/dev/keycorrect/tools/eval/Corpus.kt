package dev.keycorrect.tools.eval

import dev.keycorrect.core.dictionary.Dictionary
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random

/** Which part of the held-out file to draw sentences from. */
enum class Split(private val dev: Boolean?) {
    Dev(true),
    Test(false),
    All(null);

    fun contains(isDev: Boolean) = dev == null || dev == isDev
}

/** What to do with Tatoeba's placeholder names, which the dictionary deliberately excludes. */
enum class NameMode {
    /**
     * The name token is committed as written but not typed through the engine and not scored; the
     * n-gram chain breaks at it, exactly like at dictionary-build time. (Default.)
     */
    Skip,
    /** Replace the name by a stand-in that the dictionary knows ([Corpus.SUBSTITUTES]). */
    Substitute,
    /**
     * Treat the names like any other word (they are out of vocabulary: expect false corrections).
     */
    Keep,
}

/** One word of a sentence and the non-word text (spaces, punctuation) in front of it. */
data class Token(
    val gap: String,
    val text: String,
    val evaluated: Boolean,
    val inDictionary: Boolean,
)

/** A sentence cut into [Token]s plus the text after the last word. */
data class ParsedSentence(val tokens: List<Token>, val trailing: String)

object Corpus {
    /**
     * Tatoeba placeholder names that the dictionary excludes (`dictbuild --tatoeba-ignore-names`).
     */
    val PLACEHOLDER_NAMES = setOf("Tom", "Mary")

    /** Stand-ins for [NameMode.Substitute]; used only if the dictionary has the word. */
    val SUBSTITUTES = mapOf("Tom" to "John", "Mary" to "Anna")

    /**
     * Reads the held-out file and returns the sentences of [split], shuffled by [seed], as
     * `[offset, offset+count)` (count 0 = all). The dev/test partition is fixed (a hash of the line
     * number, independent of [seed]): [DEV_PERCENT] % of the lines are dev, used for tuning and
     * sweeps; the rest is test, used for the numbers we report.
     */
    fun sample(path: Path, count: Int, offset: Int, seed: Long, split: Split): List<String> {
        val all = Files.readAllLines(path)
        val pool =
            all.indices.filter { all[it].isNotBlank() && split.contains(isDev(it)) }.toMutableList()
        pool.shuffle(Random(seed xor SHUFFLE_SALT))
        val from = offset.coerceIn(0, pool.size)
        val to = if (count <= 0) pool.size else minOf(pool.size, from + count)
        return pool.subList(from, to).map { all[it] }
    }

    /** True if line [index] of the held-out file belongs to the dev split. */
    fun isDev(index: Int): Boolean {
        var z = index.toLong() * -0x61c8864680b583ebL + SPLIT_SALT
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return Math.floorMod(z, 100L) < DEV_PERCENT
    }

    const val DEV_PERCENT = 20

    /**
     * Cuts [sentence] into words (letters/digits with internal apostrophes or hyphens, like the
     * dictionary builder's tokenizer) and gaps. A token is *evaluated* when it can be typed through
     * the engine: no digits, not a placeholder name (per [names]).
     */
    fun parse(sentence: String, dictionary: Dictionary?, names: NameMode): ParsedSentence {
        val tokens = ArrayList<Token>()
        var i = 0
        val n = sentence.length
        val gap = StringBuilder()
        while (i < n) {
            if (!isWordChar(sentence[i])) {
                gap.append(sentence[i++])
                continue
            }
            val start = i
            i++
            while (i < n) {
                val c = sentence[i]
                if (isWordChar(c)) {
                    i++
                } else if ((isConnector(c)) && i + 1 < n && isWordChar(sentence[i + 1])) {
                    i += 2
                } else {
                    break
                }
            }
            var text = sentence.substring(start, i)
            val base = text.substringBefore('\'').substringBefore('’')
            val isName = base in PLACEHOLDER_NAMES
            var evaluated = text.none { it.isDigit() }
            if (isName) {
                when (names) {
                    NameMode.Skip -> evaluated = false
                    NameMode.Substitute -> {
                        val sub = SUBSTITUTES.getValue(base)
                        if (dictionary == null || dictionary.wordId(sub) >= 0) {
                            text = sub + text.substring(base.length)
                        } else {
                            evaluated = false
                        }
                    }
                    NameMode.Keep -> Unit
                }
            }
            tokens.add(Token(gap.toString(), text, evaluated, inDictionary(dictionary, text)))
            gap.setLength(0)
        }
        return ParsedSentence(tokens, gap.toString())
    }

    private fun inDictionary(dictionary: Dictionary?, word: String): Boolean {
        if (dictionary == null) return true
        val key = word.replace('’', '\'').lowercase()
        val node = dictionary.findNode(key)
        return node >= 0 && dictionary.isTerminal(node)
    }

    private fun isWordChar(c: Char) =
        Character.isLetterOrDigit(c) ||
            Character.getType(c).let {
                it == Character.NON_SPACING_MARK.toInt() ||
                    it == Character.COMBINING_SPACING_MARK.toInt()
            }

    private fun isConnector(c: Char) =
        c == '\'' || c == '’' || c == 'ʼ' || c == '-' || c == '‐' || c == '‑'

    private const val SHUFFLE_SALT = 0x5DEECE66DL
    private const val SPLIT_SALT = 0x2545F4914F6CDD1DL
}

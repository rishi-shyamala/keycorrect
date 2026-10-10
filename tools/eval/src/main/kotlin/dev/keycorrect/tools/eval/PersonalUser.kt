package dev.keycorrect.tools.eval

import java.util.Random

/** Knobs of the simulated personal user (docs/EVAL.md, "Learning"). */
data class PersonalUserConfig(
    /** Systematic touch offset in key widths (negative = left). */
    val biasX: Float = -0.10f,
    /** Systematic touch offset in key heights (positive = low on the key). */
    val biasY: Float = 0.20f,
    /** Personal tokens taken from the held-out set's own out-of-vocabulary names. */
    val heldoutWords: Int = 30,
    /** Invented capitalised names (people, places, pets). */
    val inventedNames: Int = 10,
    /** Invented lowercase nicknames / slang. */
    val inventedSlang: Int = 10,
    /** Probability that a typed word is replaced by a personal word. */
    val personalRate: Float = 0.04f,
    /** Zipf exponent of the personal vocabulary (1.0: the top word is ~50x the rarest of 50). */
    val zipfExponent: Double = 1.0,
    /** Probability that a sentence starts with one of the habitual phrases. */
    val phraseRate: Float = 0.03f,
)

/**
 * The simulated user's vocabulary: [words] in Zipf rank order (most used first) and a few habitual
 * [phrases] (greetings that contain the user's favourite names). Immutable; sampling takes a
 * caller-supplied [Random].
 */
class PersonalUser(
    val config: PersonalUserConfig,
    val words: List<String>,
    val phrases: List<List<String>>,
) {
    private val keys: Set<String> = words.mapTo(HashSet()) { fold(it) }
    private val cumulative: DoubleArray

    init {
        var acc = 0.0
        cumulative =
            DoubleArray(words.size) { r ->
                acc += 1.0 / Math.pow(r + 1.0, config.zipfExponent)
                acc
            }
    }

    /** True if [word] (case-insensitive) is one of the user's personal words. */
    fun isPersonal(word: String): Boolean = fold(word) in keys

    /** A personal word drawn from the Zipf distribution. */
    fun sample(rng: Random): String {
        if (words.isEmpty()) error("no personal words")
        val u = rng.nextDouble() * cumulative.last()
        var lo = 0
        var hi = cumulative.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cumulative[mid] >= u) hi = mid else lo = mid + 1
        }
        return words[lo]
    }

    companion object {
        fun fold(w: String): String = w.replace('’', '\'').lowercase()

        /** Candidate invented names; those the dictionary knows are dropped. */
        val INVENTED_NAMES =
            listOf(
                "Kovari",
                "Zephyra",
                "Ruvik",
                "Taelin",
                "Brondo",
                "Yusuri",
                "Quillan",
                "Marzipa",
                "Dovrik",
                "Xandor",
                "Ilvane",
                "Pokomo",
                "Seraphyn",
                "Tavish",
                "Oriel",
                "Wynnie",
            )

        /** Candidate invented lowercase nicknames / slang; known words are dropped. */
        val INVENTED_SLANG =
            listOf(
                "zonk",
                "blorp",
                "fenzy",
                "wumpa",
                "flibbet",
                "kablam",
                "snerkle",
                "mipli",
                "tavo",
                "grumpa",
                "quibble",
                "zibbo",
                "froopy",
                "nubbin",
            )

        /**
         * Builds a user: [config].heldoutWords of the most frequent alphabetic, capitalised,
         * out-of-vocabulary tokens in [devSentences] (names such as "Ziri" or "Sami" that really
         * occur in the held-out data), plus invented names and slang the dictionary does not know.
         * Rank order (who is used most) is shuffled by [rng].
         */
        fun build(
            config: PersonalUserConfig,
            devSentences: List<ParsedSentence>,
            isKnown: (String) -> Boolean,
            rng: Random,
        ): PersonalUser {
            val freq = HashMap<String, Int>()
            for (s in devSentences) for (t in s.tokens) {
                val w = t.text
                if (
                    t.evaluated &&
                        !t.inDictionary &&
                        w.length in 3..9 &&
                        w.all { it in 'a'..'z' || it in 'A'..'Z' } &&
                        w[0].isUpperCase() &&
                        w.drop(1).all { it.isLowerCase() } &&
                        w !in Corpus.PLACEHOLDER_NAMES &&
                        w != "Tatoeba"
                ) {
                    freq.merge(w, 1, Int::plus)
                }
            }
            val pool =
                freq.entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
                    )
                    .take(maxOf(config.heldoutWords * 4, 1))
                    .map { it.key }
                    .toMutableList()
            pool.shuffle(rng)
            val taken = LinkedHashSet<String>()
            taken.addAll(pool.take(config.heldoutWords))
            fun invented(source: List<String>, n: Int) {
                val ok =
                    source.filter { !isKnown(it) && fold(it) !in taken.map(::fold) }.toMutableList()
                ok.shuffle(rng)
                taken.addAll(ok.take(n))
            }
            invented(INVENTED_NAMES, config.inventedNames)
            invented(INVENTED_SLANG, config.inventedSlang)
            val ranked = taken.toMutableList()
            ranked.shuffle(rng)
            val names = ranked.filter { it[0].isUpperCase() }
            val slang = ranked.filter { it[0].isLowerCase() }
            val phrases = ArrayList<List<String>>()
            fun add(vararg parts: String?) {
                val p = parts.filterNotNull()
                if (p.size == parts.size && p.all { it in ranked || isKnown(it) }) phrases.add(p)
            }
            add("Thanks", names.getOrNull(0))
            add("Love", "you", names.getOrNull(1))
            add("See", "you", "at", names.getOrNull(2))
            add("Lol", slang.getOrNull(0))
            return PersonalUser(config, ranked, phrases)
        }
    }
}

/** Turns a plain sentence stream into the personal user's stream. */
object PersonalStream {
    /**
     * Walks [sentences] in order and returns sentences until [words] typeable words are collected.
     * In each sentence every evaluated, apostrophe-free word is replaced by a personal word with
     * probability `personalRate`; with probability `phraseRate` the sentence starts with one of the
     * user's habitual phrases ("Thanks Sami, ..."). Deterministic given [rng].
     */
    fun build(
        sentences: List<ParsedSentence>,
        user: PersonalUser,
        words: Int,
        isKnown: (String) -> Boolean,
        rng: Random,
    ): List<ParsedSentence> {
        val out = ArrayList<ParsedSentence>()
        var count = 0
        for (sentence in sentences) {
            if (count >= words) break
            val tokens = ArrayList<Token>(sentence.tokens.size + 4)
            if (user.phrases.isNotEmpty() && rng.nextFloat() < user.config.phraseRate) {
                val phrase = user.phrases[rng.nextInt(user.phrases.size)]
                val first = sentence.tokens.firstOrNull()
                phrase.forEachIndexed { i, w ->
                    tokens.add(
                        Token(if (i == 0) first?.gap.orEmpty() else " ", w, true, isKnown(w))
                    )
                }
                sentence.tokens.forEachIndexed { i, t ->
                    tokens.add(if (i == 0) decapitalise(t.copy(gap = ", ")) else t)
                }
            } else {
                tokens.addAll(sentence.tokens)
            }
            for (i in tokens.indices) {
                val t = tokens[i]
                if (
                    t.evaluated &&
                        t.text.none { it == '\'' || it == '’' || it == '-' } &&
                        !user.isPersonal(t.text) &&
                        t.text !in Corpus.PLACEHOLDER_NAMES &&
                        rng.nextFloat() < user.config.personalRate
                ) {
                    val w = user.sample(rng)
                    tokens[i] = Token(t.gap, w, true, isKnown(w))
                }
            }
            count += tokens.count { it.evaluated && it.text.none(Char::isDigit) }
            out.add(ParsedSentence(tokens, sentence.trailing))
        }
        return out
    }

    private val STARTERS =
        setOf(
            "the",
            "a",
            "an",
            "this",
            "that",
            "these",
            "those",
            "it",
            "we",
            "you",
            "they",
            "he",
            "she",
            "what",
            "why",
            "how",
            "when",
            "where",
            "who",
            "do",
            "did",
            "does",
            "is",
            "are",
            "was",
            "were",
            "have",
            "has",
            "can",
            "could",
            "would",
            "will",
            "my",
            "your",
            "his",
            "her",
            "our",
            "there",
            "if",
            "but",
            "and",
            "so",
            "please",
            "let",
        )

    /** "The cat" after a greeting reads "the cat"; names, "I" and contractions are left alone. */
    private fun decapitalise(t: Token): Token {
        val w = t.text
        if (w.length < 2 || w.lowercase() !in STARTERS || !w[0].isUpperCase()) return t
        return t.copy(text = w[0].lowercaseChar() + w.substring(1))
    }
}

package dev.keycorrect.core.decoder

import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.dictionary.WordFlags
import dev.keycorrect.core.learning.Personalization
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Back-off n-gram scoring against the dictionary (§3.1, §4.4) for one left context at a time.
 *
 * The context is the last two dictionary words of the current sentence ([w2], [w1]); `w1 = BOS_ID`
 * means a sentence start, -1 means unknown. Unknown words and numbers break the context (the
 * following word is scored without it). Stateful and reused across requests: not thread-safe.
 *
 * With a [Personalization] bound ([bindPersonalization]) [logProb] interpolates the personal
 * n-gram: `ln(w * P_user + (1 - w) * P_base)` where it has an estimate, the base value otherwise.
 * The personal n-gram works on words as text (its context is the last two words of the sentence,
 * dictionary or not), so it also knows the user's own words.
 */
internal class LanguageModel(private val dict: Dictionary, private val config: ScoringConfig) {
    /** Second word to the left, -1 if unknown/none. */
    var w2 = -1
        private set

    /** First word to the left, [Dictionary.BOS_ID] at a sentence start, -1 if unknown. */
    var w1 = Dictionary.BOS_ID
        private set

    /** True at the beginning of a sentence or of the text. */
    val atSentenceStart: Boolean
        get() = w1 == Dictionary.BOS_ID

    /** True when there is no text before the cursor at all. */
    var atDocumentStart = true
        private set

    private var triList = -1
    private var biList = -1
    private var lastBefore: String? = null

    private var user: Personalization? = null
    private var lnUser = 0f
    private var lnBase = 0f
    private var userContext: List<String> = emptyList()

    /** Binds [p] (null for none) for the following requests; call before [setContext]. */
    fun bindPersonalization(p: Personalization?, weight: Float) {
        user = if (p === Personalization.None || weight <= 0f) null else p
        lnUser = ln(weight.coerceIn(1e-6f, 1f - 1e-6f))
        lnBase = ln(1f - weight.coerceIn(1e-6f, 1f - 1e-6f))
        if (user == null) userContext = emptyList()
        lastBefore = null
    }

    /** True if a personal n-gram is bound. */
    val hasUser: Boolean
        get() = user != null

    /** The words the personal n-gram sees as left context (≤ 2, normalised, case kept). */
    val userContextWords: List<String>
        get() = userContext

    /**
     * Interpolates the personal estimate for [word] after the current context into [base]; [base]
     * itself where the personal model has no estimate.
     */
    fun blend(base: Float, word: String): Float {
        val p = user ?: return base
        val u = p.userLogProb(userContext, word) ?: return base
        if (u.isNaN()) return base
        val a = u + lnUser
        val b = base + lnBase
        val m = max(a, b)
        return m + ln(exp(a - m) + exp(b - m))
    }

    /** Log-probability of the rarest word; typed gibberish is assumed barely less likely. */
    val oovLogProb: Float = dict.logProb(dict.wordCount - 1) + config.oovOffset

    private val tmpIds = IntArray(POOL)
    private val tmpLp = FloatArray(POOL)
    private val poolIds = IntArray(POOL * 3)
    private val poolLp = FloatArray(POOL * 3)

    // ------------------------------------------------------------ context

    /** Parses the text before the composing word. Cheap to call repeatedly with the same text. */
    fun setContext(before: String) {
        if (before != lastBefore) {
            lastBefore = before
            parse(before)
            if (user != null) userContext = Text.lastSentenceWords(before, USER_CONTEXT_WORDS)
        }
        bind()
    }

    /** Sets the context directly (used while extending phrases). */
    fun setContext(w2: Int, w1: Int) {
        this.w2 = w2
        this.w1 = w1
        lastBefore = null
        atDocumentStart = false
        if (user != null) {
            userContext =
                listOf(w2, w1).filter { it >= Dictionary.FIRST_WORD_ID }.map { dict.word(it) }
        }
        bind()
    }

    private fun bind() {
        triList = if (w2 >= 0 && w1 >= 0) dict.trigramList(w2, w1) else -1
        biList = if (w1 >= 0) dict.bigramList(w1) else -1
    }

    private fun parse(before: String) {
        w2 = -1
        w1 = Dictionary.BOS_ID
        atDocumentStart = true
        var i = maxOf(0, before.length - MAX_CONTEXT_CHARS)
        // Don't start in the middle of a word cut by the length bound.
        while (
            i > 0 &&
                i < before.length &&
                Text.isWordChar(before[i - 1]) &&
                Text.isWordChar(before[i])
        ) i++
        while (i < before.length) {
            val c = before[i]
            when {
                Text.isWordChar(c) -> {
                    val start = i
                    i++
                    while (i < before.length) {
                        val d = before[i]
                        if (Text.isWordChar(d)) {
                            i++
                        } else if (
                            (Text.isApostrophe(d) || Text.isHyphen(d)) &&
                                i + 1 < before.length &&
                                Text.isWordChar(before[i + 1])
                        ) {
                            i += 2
                        } else {
                            break
                        }
                    }
                    val id = resolve(before.substring(start, i))
                    atDocumentStart = false
                    w2 = if (id < 0) -1 else w1
                    w1 = id
                }
                c == '\n' -> {
                    boundary()
                    i++
                }
                Text.isSentenceEnd(c) -> {
                    val next = if (i + 1 < before.length) before[i + 1] else ' '
                    if (next.isWhitespace()) boundary()
                    i++
                }
                else -> i++
            }
        }
    }

    private fun boundary() {
        if (!atDocumentStart) {
            w2 = -1
            w1 = Dictionary.BOS_ID
        }
    }

    /** Id of the dictionary word [token] (exact surface preferred), or -1. */
    private fun resolve(token: String): Int {
        val node = dict.findNode(Text.key(token))
        if (node < 0 || !dict.isTerminal(node)) return -1
        val count = dict.wordIdCountAt(node)
        if (count == 1) return dict.wordIdAt(node, 0)
        for (k in 0 until count) {
            val id = dict.wordIdAt(node, k)
            if (dict.word(id) == token) return id
        }
        return dict.wordIdAt(node, 0)
    }

    /**
     * The word right after the cursor if it is a dictionary word in the same sentence and the
     * cursor is not inside a word; -1 otherwise.
     */
    fun parseRightContext(after: String): Int {
        if (after.isEmpty() || Text.isWordChar(after[0])) return -1
        var i = 0
        while (i < after.length && !Text.isWordChar(after[i])) {
            if (after[i] == '\n' || Text.isSentenceEnd(after[i])) return -1
            i++
        }
        if (i >= after.length) return -1
        val start = i
        i++
        while (i < after.length) {
            val d = after[i]
            if (Text.isWordChar(d)) {
                i++
            } else if (
                (Text.isApostrophe(d) || Text.isHyphen(d)) &&
                    i + 1 < after.length &&
                    Text.isWordChar(after[i + 1])
            ) {
                i += 2
            } else {
                break
            }
        }
        return resolve(after.substring(start, i))
    }

    // ------------------------------------------------------------ scoring

    /**
     * `ln P(id | w2, w1)` with back-off trigram -> bigram -> unigram: a missing trigram costs
     * [ScoringConfig.trigramBackoffPenalty], a missing bigram [ScoringConfig.bigramBackoffPenalty]
     * (both when both miss), matching the dictionary build's assumptions.
     */
    fun logProb(id: Int): Float {
        val base = baseLogProb(id)
        return if (user != null && id >= Dictionary.FIRST_WORD_ID) blend(base, dict.word(id))
        else base
    }

    /** [logProb] without the personal n-gram. */
    fun baseLogProb(id: Int): Float {
        var penalty = 0f
        if (triList >= 0) {
            val v = dict.logProbInTrigramList(triList, id)
            if (!v.isNaN()) return v
            penalty += config.trigramBackoffPenalty
        }
        if (biList >= 0) {
            val v = dict.logProbInBigramList(biList, id)
            if (!v.isNaN()) return v + penalty
            penalty += config.bigramBackoffPenalty
        }
        return dict.logProb(id) + penalty
    }

    /** `ln P(next | id)` for the right context, backing off to the unigram of [next]. */
    fun rightLogProb(id: Int, next: Int): Float {
        if (next < 0) return 0f
        if (id >= 0) {
            val v = dict.logProbInBigramList(dict.bigramList(id), next)
            if (!v.isNaN()) return v
        }
        return dict.logProb(next) + config.bigramBackoffPenalty
    }

    // ------------------------------------------------------------ next word

    /**
     * Fills [outIds]/[outLogProbs] with up to [want] suggestable next words after the current
     * context, best first, and returns the count. Candidates come from the trigram and bigram
     * successor lists and the most frequent words, all scored with [logProb]. With [allowEnd],
     * `</s>` may appear (so phrase building can stop at a sentence end).
     */
    fun nextWords(want: Int, outIds: IntArray, outLogProbs: FloatArray, allowEnd: Boolean): Int {
        val perSource = minOf(POOL, want * 4 + 4)
        var n = 0
        if (w2 >= 0 && w1 >= 0) {
            val c = dict.topSuccessors(w2, w1, perSource, tmpIds, tmpLp)
            for (j in 0 until c) n = addToPool(n, tmpIds[j])
        }
        if (w1 >= 0) {
            val c = dict.topSuccessors(w1, perSource, tmpIds, tmpLp)
            for (j in 0 until c) n = addToPool(n, tmpIds[j])
        }
        val uniEnd = minOf(dict.wordCount, Dictionary.FIRST_WORD_ID + perSource)
        for (id in Dictionary.FIRST_WORD_ID until uniEnd) n = addToPool(n, id)
        for (j in 0 until n) poolLp[j] = logProb(poolIds[j])
        sortPool(n)
        var out = 0
        var j = 0
        while (j < n && out < want && out < outIds.size) {
            val id = poolIds[j]
            val ok =
                if (id == Dictionary.EOS_ID) allowEnd
                else id >= Dictionary.FIRST_WORD_ID && suggestable(id)
            if (ok) {
                outIds[out] = id
                outLogProbs[out] = poolLp[j]
                out++
            }
            j++
        }
        return out
    }

    private fun suggestable(id: Int): Boolean =
        dict.flags(id) and (WordFlags.POSSIBLY_OFFENSIVE or WordFlags.NOT_A_WORD) == 0 &&
            Text.hasLetter(dict.word(id)) &&
            user?.isBlocked(dict.word(id)) != true

    private fun addToPool(n: Int, id: Int): Int {
        for (j in 0 until n) if (poolIds[j] == id) return n
        if (n >= poolIds.size) return n
        poolIds[n] = id
        return n + 1
    }

    /** Insertion sort by log-probability descending, then id ascending; pools are tiny. */
    private fun sortPool(n: Int) {
        for (a in 1 until n) {
            val id = poolIds[a]
            val lp = poolLp[a]
            var b = a - 1
            while (b >= 0 && (poolLp[b] < lp || (poolLp[b] == lp && poolIds[b] > id))) {
                poolIds[b + 1] = poolIds[b]
                poolLp[b + 1] = poolLp[b]
                b--
            }
            poolIds[b + 1] = id
            poolLp[b + 1] = lp
        }
    }

    private companion object {
        const val POOL = 32
        const val MAX_CONTEXT_CHARS = 192
        const val USER_CONTEXT_WORDS = 2
    }
}

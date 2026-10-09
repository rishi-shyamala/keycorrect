package dev.keycorrect.tools.dictbuild

import java.text.Normalizer

/** Per-corpus token statistics. */
class SourceStats {
    var sentences = 0L
    var tokens = 0L
    var inVocab = 0L
    var oovNumeric = 0L
    var oovOther = 0L

    val oov: Long
        get() = oovNumeric + oovOther

    fun toMap(): Map<String, Any?> =
        linkedMapOf(
            "sentences" to sentences,
            "tokens" to tokens,
            "inVocabTokens" to inVocab,
            "oovTokens" to oov,
            "oovNumericTokens" to oovNumeric,
            "oovRate" to rate(oov, tokens),
            "oovRateExcludingNumeric" to rate(oovOther, tokens - oovNumeric),
        )

    private fun rate(a: Long, b: Long) = if (b == 0L) 0.0 else a.toDouble() / b
}

/**
 * Unigram / bigram / trigram counts over vocabulary tokens, kept per corpus (channels 0 and 1).
 *
 * Each sentence starts with the `<s>` marker (id 0), so the first word yields bigram (`<s>`, w1)
 * and the second word trigram (`<s>`, w1, w2). A token outside the vocabulary breaks the chain: no
 * n-gram spans it, and the next in-vocabulary token starts a fresh chain (no `<s>` context, because
 * it is no longer sentence-initial).
 *
 * Packed keys: 21 bits per word id (up to ~2M words), bigram `a<<21|b`, trigram `a<<42|b<<21|c`.
 */
class CorpusCounts(private val vocab: Vocabulary) {
    init {
        require(vocab.size < (1 shl BITS)) { "vocabulary too large for 21-bit ids" }
    }

    val unigrams = Array(CHANNELS) { IntArray(vocab.size + 1) } // index 0 = sentence starts
    val bigrams = LongCountMap(channels = CHANNELS)
    val trigrams = LongCountMap(channels = CHANNELS)
    val stats = Array(CHANNELS) { SourceStats() }

    /** Counts one sentence of raw text for corpus [channel]. */
    fun addSentence(rawText: String, channel: Int) {
        var text: CharSequence = rawText
        if (
            rawText.any { it.code > 127 } && !Normalizer.isNormalized(rawText, Normalizer.Form.NFC)
        ) {
            text = Normalizer.normalize(rawText, Normalizer.Form.NFC)
        }
        val st = stats[channel]
        val uni = unigrams[channel]
        st.sentences++
        state.p1 = 0
        state.p2 = -1
        var first = true
        Tokenizer.tokenize(text) { token ->
            st.tokens++
            val id = vocab.lookup(token, first)
            when {
                id != 0 -> {
                    st.inVocab++
                    emit(id, channel, uni)
                }
                else -> if (!resolveFallback(token, first, channel, uni, st)) breakChain(token, st)
            }
            first = false
        }
        if (st.tokens > 0) uni[0]++
    }

    private class Chain {
        var p1 = 0
        var p2 = -1
    }

    private val state = Chain()

    private fun breakChain(token: String, st: SourceStats) {
        if (Tokenizer.isNumeric(token)) st.oovNumeric++ else st.oovOther++
        state.p1 = -1
        state.p2 = -1
    }

    /**
     * Fallbacks for tokens missing from the vocabulary: a possessive `'s` on a known word maps to
     * the word, and a hyphenated compound whose parts are all known is counted as its parts.
     */
    private fun resolveFallback(
        token: String,
        first: Boolean,
        channel: Int,
        uni: IntArray,
        st: SourceStats,
    ): Boolean {
        if (token.endsWith("'s") || token.endsWith("'S")) {
            val base = vocab.lookup(token.substring(0, token.length - 2), first)
            if (base != 0) {
                st.inVocab++
                emit(base, channel, uni)
                return true
            }
        }
        if (token.indexOf('-') > 0) {
            val parts = token.split('-')
            val ids = IntArray(parts.size)
            for (k in parts.indices) {
                ids[k] = vocab.lookup(parts[k], first && k == 0)
                if (ids[k] == 0) return false
            }
            st.inVocab++
            for (id in ids) emit(id, channel, uni)
            return true
        }
        return false
    }

    private fun emit(id: Int, channel: Int, uni: IntArray) {
        uni[id]++
        val p1 = state.p1
        val p2 = state.p2
        if (p1 >= 0) {
            bigrams.increment((p1.toLong() shl BITS) or id.toLong(), channel)
            if (p2 >= 0) {
                trigrams.increment(
                    (p2.toLong() shl (2 * BITS)) or (p1.toLong() shl BITS) or id.toLong(),
                    channel,
                )
            }
        }
        state.p2 = p1
        state.p1 = id
    }

    companion object {
        const val BITS = 21
        const val MASK = (1L shl BITS) - 1
        const val CHANNELS = 2
        const val WIKI = 0
        const val TATOEBA = 1
    }
}

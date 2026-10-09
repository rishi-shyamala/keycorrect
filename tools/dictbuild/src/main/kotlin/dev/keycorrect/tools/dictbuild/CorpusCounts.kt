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
class CorpusCounts(
    private val vocab: Vocabulary,
    /**
     * Exact-case tokens treated as out-of-vocabulary in Tatoeba sentences only (they break the
     * n-gram chain and add no counts). Tatoeba's placeholder names ("Tom", "Mary") would otherwise
     * dominate predictions.
     */
    private val tatoebaIgnored: Set<String> = emptySet(),
) {
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
            ids.clear()
            if (channel == TATOEBA && token in tatoebaIgnored) {
                breakChain(token, st)
            } else if (vocab.resolve(token, first, ids)) {
                st.inVocab++
                for (k in 0 until ids.size) emit(ids[k], channel, uni)
            } else {
                breakChain(token, st)
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
    private val ids = IdBuffer()

    private fun breakChain(token: String, st: SourceStats) {
        if (Tokenizer.isNumeric(token)) st.oovNumeric++ else st.oovOther++
        state.p1 = -1
        state.p2 = -1
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

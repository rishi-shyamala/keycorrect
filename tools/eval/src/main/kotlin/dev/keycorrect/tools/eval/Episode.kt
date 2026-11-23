package dev.keycorrect.tools.eval

import dev.keycorrect.core.engineapi.ComposingInput
import dev.keycorrect.core.engineapi.KeyLayoutModel
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.engineapi.TypingContext
import java.util.Random

/** The thing under test: a synchronous `PredictRequest -> PredictResult`. */
fun interface Predictor {
    fun predict(request: PredictRequest): PredictResult
}

/** What an episode simulates beyond the final autocorrect decision. */
data class EpisodeOptions(
    /** Use the true previous words as context instead of what autocorrect committed. */
    val oracleContext: Boolean = false,
    /** Simulate the user tapping a suggestion as soon as it is in the top 3. */
    val completion: Boolean = true,
    /** Evaluate next-word prediction (and phrases) before each word. */
    val nextWord: Boolean = true,
    /** Longest phrase requested at word boundaries; <2 turns phrase evaluation off. */
    val phraseWords: Int = 6,
    val maxCandidates: Int = 3,
    /**
     * Latency runs call the engine after every key, even once a suggestion would have been tapped.
     */
    val exhaustivePrefixes: Boolean = false,
    /** Rows to keep for the failures CSV (per run); 0 disables. */
    val failureLimit: Int = 0,
)

/**
 * Types one sentence word by word through a [Predictor] and records [Metrics]. Sentences are
 * independent episodes: context starts empty (as in a fresh text field).
 *
 * After each word the user "commits" what autocorrect produced (or the literal), so a wrong
 * correction stays in the context of the following words (unless [EpisodeOptions.oracleContext]).
 */
class EpisodeRunner(
    private val predictor: Predictor,
    private val layout: KeyLayoutModel,
    private val typist: Typist,
    private val options: EpisodeOptions,
    private val seed: Long,
) {
    fun run(
        sentenceIndex: Int,
        sentence: ParsedSentence,
        metrics: Metrics,
        failures: MutableList<FailureRow>? = null,
    ) {
        metrics.sentences++
        val committed = StringBuilder()
        val truth = StringBuilder()
        val tokens = sentence.tokens
        val partners = HashMap<Int, Token>()
        var next = 0
        while (next < tokens.size - 1) {
            val partner = spacePartner(sentenceIndex, tokens, next)
            if (partner != null) partners[next++] = partner
            next++
        }
        // A token typed together with the one before it is not a word of its own.
        val joined = partners.keys.map { it + 1 }.toSet()
        for ((k, token) in tokens.withIndex().filter { it.index !in joined }) {
            committed.append(token.gap)
            truth.append(token.gap)
            val partner = partners[k]
            val merged = partner != null
            val target = if (partner != null) token.text + " " + partner.text else token.text
            if (!token.evaluated || !typist.canType(token.text)) {
                committed.append(token.text)
                truth.append(token.text)
                metrics.skippedTokens++
                continue
            }
            val context = if (options.oracleContext) truth else committed
            val before = tail(context)

            // Before the first key: next word + phrase (and the tap-instead-of-typing option).
            var nextWordHit = false
            if (options.nextWord && !merged) {
                val result =
                    predictor.predict(
                        PredictRequest(
                            TypingContext(before),
                            ComposingInput.Empty,
                            layout,
                            options.maxCandidates,
                            maxPhraseWords = options.phraseWords,
                        )
                    )
                val top = result.candidates.take(options.maxCandidates).map { it.text }
                metrics.nextWord.add(k, token.inDictionary, top, token.text)
                nextWordHit = top.any { it.equals(token.text, ignoreCase = true) }
                if (options.phraseWords >= 2) {
                    metrics.nextWord.addPhrase(
                        result.phrase?.text,
                        tokens.subList(k, tokens.size).map { it.text },
                    )
                }
            }

            val typed =
                typist.type(
                    if (partner != null) token.text + partner.text else token.text,
                    Random(mix(seed, sentenceIndex, k)),
                )
            val result =
                predictor.predict(
                    PredictRequest(
                        TypingContext(before),
                        typed.input,
                        layout,
                        options.maxCandidates,
                        autocorrectAllowed = true,
                    )
                )
            val literal = typed.text
            val fired = result.autocorrect != null && result.autocorrect!!.text != literal
            val word = if (fired) result.autocorrect!!.text else literal
            val top = result.candidates.take(options.maxCandidates).map { it.text }

            var keysCompletion: Int? = null
            var keysNextWord: Int? = null
            if (options.completion && !merged) {
                var used = literal.length
                for (i in 1..literal.length - 2) {
                    val prefix =
                        ComposingInput(
                            literal.substring(0, i),
                            typed.input.touches.subList(0, i),
                        )
                    val r =
                        predictor.predict(
                            PredictRequest(
                                TypingContext(before),
                                prefix,
                                layout,
                                options.maxCandidates,
                                autocorrectAllowed = false,
                            )
                        )
                    if (
                        r.candidates.take(options.maxCandidates).any {
                            it.text.equals(token.text, ignoreCase = true)
                        }
                    ) {
                        if (used > i + 1) used = i + 1
                        if (!options.exhaustivePrefixes) break
                    }
                }
                keysCompletion = used
                keysNextWord = if (nextWordHit) 1 else used
            }

            val outcome =
                WordOutcome(
                    target = target,
                    literal = literal,
                    committed = word,
                    top = top,
                    autocorrectFired = fired,
                    typo = typed.typo,
                    touchSlips = typed.touchSlips,
                    targetInDictionary = token.inDictionary,
                    typedKeys = literal.length,
                    keysWithCompletion = keysCompletion,
                    keysWithNextWord = if (options.nextWord) keysNextWord else null,
                    merged = merged,
                )
            metrics.record(outcome)
            metrics.decisions?.add(
                WordDecision(sentenceIndex, k, outcome.literalCorrect, outcome.finalCorrect, word)
            )
            val category = outcome.failure()
            if (category != null && failures != null && failures.size < options.failureLimit) {
                failures.add(
                    FailureRow(
                        sentenceIndex,
                        category,
                        target,
                        literal,
                        word,
                        top,
                        typed.typo,
                        before.takeLast(CONTEXT_TAIL_CHARS),
                    )
                )
            }
            committed.append(word)
            truth.append(target)
        }
        committed.append(sentence.trailing)
        truth.append(sentence.trailing)
    }

    /**
     * The next token when it is typed together with [tokens]`[k]` (no space, one target of two
     * words), or null. Needs `TypistConfig.spaceOmissionRate` > 0, two typable in-dictionary words
     * separated by exactly one space, and a draw from a stream of its own.
     */
    private fun spacePartner(sentenceIndex: Int, tokens: List<Token>, k: Int): Token? {
        val rate = typist.config.spaceOmissionRate
        val a = tokens[k]
        val b = tokens[k + 1]
        val eligible =
            rate > 0f &&
                b.gap == " " &&
                a.evaluated &&
                b.evaluated &&
                a.inDictionary &&
                b.inDictionary &&
                typist.canType(a.text) &&
                typist.canType(b.text)
        val dropped =
            eligible && Random(mix(seed xor SPACE_STREAM, sentenceIndex, k)).nextFloat() < rate
        return if (dropped) b else null
    }

    private fun tail(text: CharSequence): String =
        if (text.length <= TypingContext.MAX_BEFORE_CHARS) text.toString()
        else text.substring(text.length - TypingContext.MAX_BEFORE_CHARS)

    companion object {
        const val CONTEXT_TAIL_CHARS = 40
        private const val SPACE_STREAM = 0x5eace5L

        /** SplitMix64-style mixing so each (sentence, word) gets an independent, stable seed. */
        fun mix(seed: Long, sentence: Int, word: Int): Long {
            var z = seed * -0x61c8864680b583ebL + sentence.toLong() * 0x632BE59BD9B4E019L + word
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}

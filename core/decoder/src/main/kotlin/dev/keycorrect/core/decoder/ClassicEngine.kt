package dev.keycorrect.core.decoder

import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.dictionary.WordFlags
import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.Capability
import dev.keycorrect.core.engineapi.EngineInfo
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.engineapi.PredictiveEngine
import kotlin.math.ln

/**
 * The built-in prediction engine (docs/PLAN.md §4.4): spatial touch model + trie search with edit
 * tolerance + back-off n-gram context, autocorrect policy, next-word prediction and phrase
 * completion, all on one [Dictionary].
 *
 * Pipeline for a word being typed: bind the touches to the layout's Gaussian key model
 * ([SpatialModel]), search the dictionary trie best-first ([TrieSearch]) for the
 * [SearchConfig.poolSize] best words by spatial + edits + unigram, add user words ([WordAligner]),
 * re-score the pool with the contextual language model ([LanguageModel]) into a decomposed
 * [ScoreBreakdown], put a matching shortcut first, then let [AutocorrectPolicy] decide whether the
 * best candidate may be applied automatically. With nothing composing, it predicts the next word
 * from the context instead.
 *
 * Threading: the engine reuses scratch buffers, so it is meant to be driven from one decoder thread
 * at a time. Calls are serialised by a lock as a safety net, never concurrent. [userWords] and
 * [shortcuts] are read on that thread and must return immutable snapshots (a new instance when the
 * data changes). [isStale] is polled while working; when it returns true the request is abandoned
 * and an empty result returned, so a newer keystroke never waits behind an old one.
 *
 * @param touchCorrection learned per-key touch offsets (Phase 3); the spatial models are rebuilt
 *   when it returns a different instance.
 */
class ClassicEngine(
    private val dictionary: Dictionary,
    val config: ClassicConfig = ClassicConfig(),
    private val userWords: () -> Collection<String> = { emptyList() },
    private val shortcuts: () -> Map<String, String> = { emptyMap() },
    private val touchCorrection: () -> TouchCorrection = { TouchCorrection.Identity },
    private val isStale: () -> Boolean = { false },
) : PredictiveEngine {
    /** The autocorrect policy; adjust [AutocorrectPolicy.threshold] to tune aggressiveness. */
    val autocorrectPolicy = AutocorrectPolicy(config.autocorrect)

    override val info =
        EngineInfo(
            id = ID,
            name = "Classic",
            capabilities = setOf(Capability.Predict),
            languages = setOf(dictionary.metadata.languageTag),
        )

    private val spatialModels = SpatialModelCache(config.spatial)
    private val scorer = TouchScorer(config.spatial, config.search.maxInputChars)
    private val lm = LanguageModel(dictionary, config.scoring)
    private val search =
        TrieSearch(
            dictionary,
            config.search,
            config.edits,
            config.scoring.lambda,
            config.scoring.completionCharPenalty,
        )
    private val aligner =
        WordAligner(
            config.edits,
            config.scoring.completionCharPenalty,
            config.search.maxCompletionChars,
            config.search.maxInputChars,
        )
    private val lexicon = UserLexicon(dictionary, config.user, userWords, shortcuts)
    private val nextIds = IntArray(MAX_NEXT_WORDS)
    private val nextLogProbs = FloatArray(MAX_NEXT_WORDS)
    private val phraseIds = IntArray(1)
    private val phraseLogProbs = FloatArray(1)

    override suspend fun load() = Unit

    override suspend fun unload() = Unit

    override suspend fun predict(request: PredictRequest): PredictResult = predictNow(request)

    /**
     * Synchronous fast path (decoder thread). Returns an empty result when [stale] fires; the
     * default is the constructor's `isStale`.
     */
    fun predictNow(request: PredictRequest, stale: () -> Boolean = isStale): PredictResult {
        val ranked = predictRanked(request, stale)
        return PredictResult(
            candidates = ranked.candidates.map { it.toCandidate() },
            literal = ranked.literal?.toCandidate(),
            autocorrect = ranked.autocorrect?.toCandidate(),
            phrase = ranked.phrase,
            sequence = request.sequence,
        )
    }

    /** Like [predictNow] but keeps each candidate's [ScoreBreakdown] for the learning layer. */
    @Synchronized
    fun predictRanked(request: PredictRequest, stale: () -> Boolean = isStale): RankedPrediction {
        if (stale()) return RankedPrediction.Empty
        return if (request.composing.isEmpty) predictNextWord(request)
        else predictWord(request, stale)
    }

    // ------------------------------------------------------------------ typing a word

    private fun predictWord(request: PredictRequest, stale: () -> Boolean): RankedPrediction {
        val input = request.composing
        val text = input.text
        val key = Text.key(text)
        val field = request.context.field
        val scoring = config.scoring
        lexicon.refresh()
        val shortcut = lexicon.shortcutFor(key)?.let { shortcutCandidate(it, 0f) }

        if (
            text.length > config.search.maxInputChars ||
                Text.isNonProse(text) ||
                text.contains("://")
        ) {
            val literal = RankedCandidate(text, 0f, literalBreakdown(0f, 0f, 0f), -1)
            return RankedPrediction(literal, listOfNotNull(shortcut), null, null)
        }

        lm.setContext(request.context.before)
        val right = lm.parseRightContext(request.context.after)
        scorer.bind(spatialModels.get(request.layout, touchCorrection()), input)

        // The literal: what the user typed, scored like any candidate.
        var literalNode = dictionary.findNode(key)
        if (literalNode >= 0 && !dictionary.isTerminal(literalNode)) literalNode = -1
        val literalIsUser = lexicon.containsWord(key)
        var literalLm = lm.oovLogProb
        var literalRight = if (right >= 0) lm.rightLogProb(-1, right) else 0f
        if (literalNode >= 0) {
            literalLm = Float.NEGATIVE_INFINITY
            for (j in 0 until dictionary.wordIdCountAt(literalNode)) {
                val id = dictionary.wordIdAt(literalNode, j)
                val v = lm.logProb(id)
                if (v > literalLm) {
                    literalLm = v
                    if (right >= 0) literalRight = lm.rightLogProb(id, right)
                }
            }
        }
        if (literalIsUser && config.user.wordLogProb > literalLm)
            literalLm = config.user.wordLogProb
        val literalBreakdown =
            literalBreakdown(scorer.literalLogLikelihood(), literalLm, literalRight)
        val literal = RankedCandidate(text, literalBreakdown.total(scoring), literalBreakdown, -1)

        if (stale()) return RankedPrediction.Empty
        if (!search.run(scorer, literalNode, stale)) return RankedPrediction.Empty

        val ranked = ArrayList<RankedCandidate>(search.resultCount + 8)
        collectDictionaryCandidates(text, right, ranked)
        collectUserCandidates(text, key, right, ranked)
        if (stale()) return RankedPrediction.Empty
        ranked.sortWith(RANK_ORDER)

        // Candidate strip: shortcut first, then the capitalised pronoun fix, then the ranking.
        val out = ArrayList<RankedCandidate>(request.maxCandidates)
        if (shortcut != null) out.add(shortcut)
        val pronoun = capitalizedPronoun(key, text, literal, ranked.firstOrNull())
        if (pronoun != null) out.add(pronoun)
        for (c in ranked) {
            if (out.size >= request.maxCandidates) break
            if (c.text != text) out.add(c)
        }

        val autocorrect =
            if (
                pronoun != null &&
                    request.autocorrectAllowed &&
                    !autocorrectPolicy.blocksField(field)
            ) {
                pronoun
            } else {
                autocorrectPolicy.choose(
                    AutocorrectInput(
                        literal = text,
                        literalScore = literal.score,
                        literalIsDictionaryWord = literalNode >= 0,
                        literalIsUserWord = literalIsUser,
                        candidates = ranked.filter { it.text != text },
                        field = field,
                        allowed = request.autocorrectAllowed,
                    )
                )
            }

        var phrase: Candidate? = null
        if (request.maxPhraseWords >= 2 && !autocorrectPolicy.blocksField(field)) {
            val base = ranked.firstOrNull { it.wordId >= 0 && it.text != text }
            if (base != null) {
                phrase =
                    buildPhrase(
                        base.text,
                        base.wordId,
                        anchoredLogProb = 0f,
                        request.maxPhraseWords,
                    )
            }
        }
        return RankedPrediction(literal, out, autocorrect, phrase)
    }

    private fun literalBreakdown(spatial: Float, lmValue: Float, rightLm: Float) =
        ScoreBreakdown(spatial, lmValue, rightLm, 0f, 0, 0, CandidateSource.Literal)

    private fun shortcutCandidate(expansion: String, score: Float) =
        RankedCandidate(
            expansion,
            score,
            ScoreBreakdown(0f, 0f, 0f, 0f, 0, 0, CandidateSource.Shortcut),
            -1,
        )

    /** Turns the trie search results into candidates scored with the contextual LM. */
    private fun collectDictionaryCandidates(
        literal: String,
        right: Int,
        out: MutableList<RankedCandidate>,
    ) {
        for (k in 0 until search.resultCount) {
            val node = search.resultNode[k]
            var bestId = -1
            var bestLm = Float.NEGATIVE_INFINITY
            for (j in 0 until dictionary.wordIdCountAt(node)) {
                val id = dictionary.wordIdAt(node, j)
                if (dictionary.flags(id) and EXCLUDED_FLAGS != 0) continue
                val v = lm.logProb(id)
                if (v > bestLm) {
                    bestLm = v
                    bestId = id
                }
            }
            if (bestId < 0) continue
            val rightLm = if (right >= 0) lm.rightLogProb(bestId, right) else 0f
            val breakdown =
                ScoreBreakdown(
                    spatial = search.resultSpatial[k],
                    lm = bestLm,
                    rightLm = rightLm,
                    edits = search.resultPenalty[k],
                    editCount = search.resultEdits[k],
                    completionChars = search.resultExtra[k],
                    source = CandidateSource.Dictionary,
                )
            out.add(
                RankedCandidate(
                    Casing.apply(dictionary.word(bestId), literal),
                    breakdown.total(config.scoring),
                    breakdown,
                    bestId,
                )
            )
        }
    }

    /** Matches the input against the user's own words (those not already in the dictionary). */
    private fun collectUserCandidates(
        literal: String,
        literalKey: String,
        right: Int,
        out: MutableList<RankedCandidate>,
    ) {
        val budget = config.edits.budget(scorer.length)
        val rightLm = if (right >= 0) lm.rightLogProb(-1, right) else 0f
        for (u in 0 until lexicon.size) {
            if (lexicon.isInDictionary(u)) continue
            val userKey = lexicon.key(u)
            if (userKey == literalKey || !aligner.align(scorer, userKey, budget)) continue
            val breakdown =
                ScoreBreakdown(
                    spatial = aligner.resultSpatial,
                    lm = config.user.wordLogProb,
                    rightLm = rightLm,
                    edits = aligner.resultPenalty,
                    editCount = aligner.resultEdits,
                    completionChars = aligner.resultExtra,
                    source = CandidateSource.UserDictionary,
                )
            out.add(
                RankedCandidate(
                    Casing.apply(lexicon.surface(u), literal),
                    breakdown.total(config.scoring),
                    breakdown,
                    -1,
                )
            )
        }
    }

    /**
     * "i" -> "I", "i'm" -> "I'm": the one case-only fix that is applied even though the literal is
     * a dictionary word. Returns a candidate placed above the ranking, or null.
     */
    private fun capitalizedPronoun(
        key: String,
        text: String,
        literal: RankedCandidate,
        best: RankedCandidate?,
    ): RankedCandidate? {
        if (text[0] != 'i' || (key != "i" && !key.startsWith("i'"))) return null
        val fixed = "I" + key.substring(1)
        val id = dictionary.wordId(fixed)
        if (id < 0 || fixed == text) return null
        val score = maxOf(literal.score, best?.score ?: literal.score) + 1f
        return RankedCandidate(
            fixed,
            score,
            literal.breakdown.copy(source = CandidateSource.Dictionary),
            id,
        )
    }

    // ------------------------------------------------------------------ next word

    private fun predictNextWord(request: PredictRequest): RankedPrediction {
        if (autocorrectPolicy.blocksField(request.context.field)) return RankedPrediction.Empty
        lm.setContext(request.context.before)
        val sentenceStart = lm.atSentenceStart
        val atDocumentStart = lm.atDocumentStart
        val want = request.maxCandidates.coerceIn(1, MAX_NEXT_WORDS)
        val count = lm.nextWords(want, nextIds, nextLogProbs, allowEnd = false)
        val seen = HashSet<String>()
        val out = ArrayList<RankedCandidate>(count)
        for (j in 0 until count) {
            var word = dictionary.word(nextIds[j])
            if (sentenceStart && word == word.lowercase()) word = Text.capitalizeFirst(word)
            if (!seen.add(word.lowercase())) continue
            val lp = nextLogProbs[j]
            out.add(
                RankedCandidate(
                    word,
                    lp,
                    ScoreBreakdown(0f, lp, 0f, 0f, 0, 0, CandidateSource.Dictionary),
                    nextIds[j],
                )
            )
        }
        var phrase: Candidate? = null
        if (request.maxPhraseWords >= 2 && !atDocumentStart && out.isNotEmpty()) {
            val first = out[0]
            if (first.score >= ln(config.phrase.minStepProbability)) {
                phrase = buildPhrase(first.text, first.wordId, first.score, request.maxPhraseWords)
            }
        }
        return RankedPrediction(null, out, null, phrase)
    }

    // ------------------------------------------------------------------ phrases

    /**
     * Greedily extends [firstText] (word [firstId]) with the most likely successor while each step
     * has probability >= [PhraseConfig.minStepProbability]. [anchoredLogProb] is the
     * log-probability counted for the first word: 0 when the user already committed to it by
     * typing, its probability for a pure next-word prediction. Returns null unless the phrase has
     * at least two words and its probability reaches [PhraseConfig.minTotalProbability]. Leaves
     * [lm] with the phrase as context.
     */
    private fun buildPhrase(
        firstText: String,
        firstId: Int,
        anchoredLogProb: Float,
        maxWords: Int,
    ): Candidate? {
        val minStep = ln(config.phrase.minStepProbability)
        val minTotal = ln(config.phrase.minTotalProbability)
        val sb = StringBuilder(firstText)
        var words = 1
        var total = anchoredLogProb
        lm.setContext(lm.w1, firstId)
        while (words < maxWords) {
            val n = lm.nextWords(1, phraseIds, phraseLogProbs, allowEnd = true)
            if (n == 0 || phraseIds[0] == Dictionary.EOS_ID || phraseLogProbs[0] < minStep) break
            total += phraseLogProbs[0]
            sb.append(' ').append(dictionary.word(phraseIds[0]))
            words++
            lm.setContext(lm.w1, phraseIds[0])
        }
        if (words < 2 || total < minTotal) return null
        return Candidate(sb.toString(), total, CandidateSource.Dictionary, isPhrase = true)
    }

    companion object {
        const val ID = "classic"
        private const val MAX_NEXT_WORDS = 16
        private const val EXCLUDED_FLAGS = WordFlags.POSSIBLY_OFFENSIVE or WordFlags.NOT_A_WORD

        /** Best score first; ties by source, then word id, then text: fully deterministic. */
        private val RANK_ORDER =
            Comparator<RankedCandidate> { a, b ->
                val byScore = b.score.compareTo(a.score)
                if (byScore != 0) return@Comparator byScore
                val bySource = a.source.compareTo(b.source)
                if (bySource != 0) return@Comparator bySource
                val byId = a.wordId.compareTo(b.wordId)
                if (byId != 0) byId else a.text.compareTo(b.text)
            }
    }
}

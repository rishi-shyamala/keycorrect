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
import dev.keycorrect.core.learning.Personalization
import dev.keycorrect.core.learning.RankingFeatures
import dev.keycorrect.core.learning.contextBucket
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ln
import kotlin.random.Random

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
 * Personalization (docs/PLAN.md §6.1): [personalization] is read once per decode and must return an
 * immutable snapshot (a new instance when the learned data changes). It is used for
 * - the language model: the personal n-gram is interpolated into every LM score (candidates, the
 *   literal, user words, next-word predictions and phrase steps; `UserDataConfig.userNgramWeight`);
 *   next-word candidates still come from the dictionary's successor lists since the personal model
 *   cannot enumerate its own;
 * - correction memory: `correctionBias` is added to every non-literal candidate's score; pairs with
 *   `isSuppressed` are never autocorrected but may still appear in the suggestion strip;
 *   `isBlocked` words are never suggested (candidates, next words, phrases);
 * - casing: `preferredCasing` replaces the dictionary casing when the literal has no capitals;
 * - touch: `touchOffset(touchKey, c)` shifts each key's Gaussian mean by that many key sizes;
 * - gates: `autocorrectThresholdDelta` shifts the autocorrect threshold (see
 *   [AutocorrectInput.thresholdDelta]); `phraseGateDelta` is added to the ln-domain phrase
 *   thresholds (positive = stricter);
 * - ranking: `rankerScore` of each candidate's [RankingFeatures] is added to its final score (word
 *   decodes only). [featuresFor] exposes the features for learning. With [Personalization.None] the
 *   engine behaves exactly as without it.
 *
 * Tie rule (§6.3 row 22): see [AutocorrectPolicy]. [random] picks between exactly (± `tieEpsilon`)
 * tied candidates for an unknown literal; inject a seeded one for determinism.
 *
 * @param touchCorrection learned per-key touch offsets (Phase 3); the spatial models are rebuilt
 *   when it returns a different instance.
 * @param touchKey `InputProfile.touchKey` of the current layout/device, or null when unknown (then
 *   no personal touch offsets are applied).
 */
class ClassicEngine(
    private val dictionary: Dictionary,
    val config: ClassicConfig = ClassicConfig(),
    private val userWords: () -> Collection<String> = { emptyList() },
    private val shortcuts: () -> Map<String, String> = { emptyMap() },
    private val touchCorrection: () -> TouchCorrection = { TouchCorrection.Identity },
    private val isStale: () -> Boolean = { false },
    private val personalization: () -> Personalization = { Personalization.None },
    private val touchKey: () -> String? = { null },
    private val random: Random? = null,
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
    private val contractions = ContractionIndex(dictionary)
    private val lexicon = UserLexicon(dictionary, config.user, userWords, shortcuts)
    private val nextIds = IntArray(MAX_NEXT_WORDS)
    private val nextLogProbs = FloatArray(MAX_NEXT_WORDS)
    private val phraseIds = IntArray(1)
    private val phraseLogProbs = FloatArray(1)

    private val lock = ReentrantLock()

    // Per-decode personalization state (set under the predictRanked lock).
    private var pers: Personalization = Personalization.None
    private var active = false
    private var bucket = ""
    private var gateDelta = 0f

    /**
     * Builds the lazily initialised structures (the contraction index) so the first [predictNow]
     * doesn't pay for them. Safe to call from any background thread, idempotent, and never throws:
     * a build failure is remembered and the structure treated as empty.
     */
    fun warmUp() {
        contractions.warmUp()
    }

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
            autocorrectRunnerUp = ranked.autocorrectRunnerUp?.toCandidate(),
        )
    }

    /**
     * The [RankingFeatures] of [candidateText] (the literal or a suggestion) for [request] under
     * the current personalization snapshot, or null if it is not among the decode's candidates. For
     * the learning layer's `Preference` updates. `rank` is the candidate's position before the
     * learned ranker score was added.
     */
    fun featuresFor(request: PredictRequest, candidateText: String): RankingFeatures? =
        lock.withLock {
            featuresLocked(request, candidateText)
        }

    /** Like [featuresFor] but returns null immediately if a decode currently holds the lock. */
    fun tryFeaturesFor(request: PredictRequest, text: String): RankingFeatures? {
        if (!lock.tryLock()) return null
        try {
            return featuresLocked(request, text)
        } finally {
            lock.unlock()
        }
    }

    private fun featuresLocked(request: PredictRequest, candidateText: String): RankingFeatures? {
        val wide =
            if (request.maxCandidates < FEATURE_POOL) request.copy(maxCandidates = FEATURE_POOL)
            else request
        val r = predictRanked(wide)
        val lit = r.literal
        if (lit != null && lit.text == candidateText) {
            return featuresOf(lit, lexicon.containsWord(Text.key(lit.text)))
        }
        val c =
            r.candidates.firstOrNull { it.text == candidateText }
                ?: r.candidates.firstOrNull { it.text.equals(candidateText, ignoreCase = true) }
        return c?.let { featuresOf(it, it.source == CandidateSource.UserDictionary) }
    }

    private fun featuresOf(c: RankedCandidate, isUserWord: Boolean) =
        RankingFeatures(
            spatial = c.breakdown.spatial,
            lm = c.breakdown.lm - c.breakdown.userLm,
            edits = c.breakdown.edits,
            completionChars = c.breakdown.completionChars,
            userLm = c.breakdown.userLm,
            correctionBias = c.breakdown.correctionBias,
            isLiteral = c.source == CandidateSource.Literal,
            isUserWord = isUserWord,
            rank = c.rank,
        )

    /**
     * A per-word seeded tie-breaker: re-decoding the same literal in the same context always
     * resolves a tie the same way, so the autocorrect applied at the separator matches the strip.
     */
    private fun wordRandom(literal: String, before: CharSequence): Random {
        var h = literal.hashCode()
        val from = maxOf(0, before.length - TIE_CONTEXT_CHARS)
        for (i in from until before.length) h = h * 31 + before[i].code
        return Random(h)
    }

    private fun rankerScore(c: RankedCandidate, isUserWord: Boolean): Float {
        val v = pers.rankerScore(featuresOf(c, isUserWord))
        return if (v.isFinite()) v else 0f
    }

    private fun bias(literal: String, candidate: String): Float {
        val v = pers.correctionBias(literal, candidate, bucket)
        return if (v.isFinite()) v else 0f
    }

    /** Preferred surface for [form] after the literal's casing, see class docs. */
    private fun shaped(form: String, literal: String): String {
        val cased = Casing.apply(form, literal)
        if (!active || literal.any { it.isUpperCase() }) return cased
        return pers.preferredCasing(Text.key(form)) ?: cased
    }

    /** Like [predictNow] but keeps each candidate's [ScoreBreakdown] for the learning layer. */
    fun predictRanked(request: PredictRequest, stale: () -> Boolean = isStale): RankedPrediction =
        lock.withLock {
            predictRankedLocked(request, stale)
        }

    private fun predictRankedLocked(
        request: PredictRequest,
        stale: () -> Boolean,
    ): RankedPrediction {
        if (stale()) return RankedPrediction.Empty
        pers = personalization()
        active = pers !== Personalization.None
        gateDelta = if (active) pers.phraseGateDelta else 0f
        lm.bindPersonalization(pers, config.user.userNgramWeight)
        bucket =
            if (active) {
                contextBucket(
                    Text.lastSentenceWords(request.context.before, 2),
                    request.context.field.name,
                )
            } else {
                ""
            }
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
        val sentenceInitial = lm.atSentenceStart
        val right = lm.parseRightContext(request.context.after)
        val tk = if (active) touchKey() else null
        val unit = if (tk != null) KeyUnitOffsets { pers.touchOffset(tk, it) } else null
        scorer.bind(
            spatialModels.get(
                request.layout,
                touchCorrection(),
                unit,
                if (unit != null) pers else null,
                tk,
            ),
            input,
        )

        // The literal: what the user typed, scored like any candidate.
        var literalNode = dictionary.findNode(key)
        if (literalNode >= 0 && !dictionary.isTerminal(literalNode)) literalNode = -1
        val literalIsUser = lexicon.containsWord(key)
        var literalBase = if (scoring.oovBackoff) lm.oovLogProbInContext() else lm.oovLogProb
        var literalLm = lm.blend(literalBase, text)
        var literalRight = if (right >= 0) lm.rightLogProb(-1, right) else 0f
        if (literalNode >= 0) {
            literalLm = Float.NEGATIVE_INFINITY
            for (j in 0 until dictionary.wordIdCountAt(literalNode)) {
                val id = dictionary.wordIdAt(literalNode, j)
                val base = lm.baseLogProb(id)
                val v = lm.blend(base, dictionary.word(id))
                if (v > literalLm) {
                    literalLm = v
                    literalBase = base
                    if (right >= 0) literalRight = lm.rightLogProb(id, right)
                }
            }
        } else if (literalIsUser && config.user.wordLogProb > literalBase) {
            literalBase = config.user.wordLogProb
            literalLm = lm.blend(literalBase, text)
        }
        val literalBreakdown =
            literalBreakdown(scorer.literalLogLikelihood(), literalLm, literalRight)
                .copy(userLm = literalLm - literalBase)
        var literal = RankedCandidate(text, literalBreakdown.total(scoring), literalBreakdown, -1)

        if (stale()) return RankedPrediction.Empty
        if (!search.run(scorer, literalNode, stale)) return RankedPrediction.Empty

        val ranked = ArrayList<RankedCandidate>(search.resultCount + 8)
        collectDictionaryCandidates(text, right, ranked)
        collectUserCandidates(text, key, right, ranked)
        if (stale()) return RankedPrediction.Empty
        ranked.sortWith(RANK_ORDER)
        if (active) {
            for (i in ranked.indices) ranked[i].rank = i
            literal = literal.withScore(literal.score + rankerScore(literal, literalIsUser))
            for (i in ranked.indices) {
                val c = ranked[i]
                ranked[i] =
                    c.withScore(
                        c.score + rankerScore(c, c.source == CandidateSource.UserDictionary)
                    )
            }
            ranked.sortWith(RANK_ORDER)
        }
        if (literalNode < 0 && !literalIsUser) addContraction(key, text, literal, ranked)

        // Candidate strip: shortcut first, then the capitalised pronoun fix, then the ranking.
        val out = ArrayList<RankedCandidate>(request.maxCandidates)
        if (shortcut != null) out.add(shortcut)
        val pronoun = capitalizedPronoun(key, text, literal, ranked.firstOrNull())
        if (pronoun != null) out.add(pronoun)
        for (c in ranked) {
            if (out.size >= request.maxCandidates) break
            if (c.text != text) out.add(c)
        }

        val decision =
            if (
                pronoun != null &&
                    request.autocorrectAllowed &&
                    !autocorrectPolicy.blocksField(field)
            ) {
                AutocorrectDecision(pronoun, null)
            } else {
                // Suppressed pairs stay in the strip but veto autocorrect for this word.
                autocorrectPolicy.decide(
                    AutocorrectInput(
                        literal = text,
                        literalScore = literal.score,
                        literalIsDictionaryWord = literalNode >= 0,
                        literalIsUserWord = literalIsUser,
                        candidates = ranked.filter { it.text != text },
                        field = field,
                        allowed = request.autocorrectAllowed,
                        sentenceInitial = sentenceInitial,
                        thresholdDelta = if (active) pers.autocorrectThresholdDelta else 0f,
                        isSuppressed = { active && pers.isSuppressed(text, it) },
                    ),
                    random ?: wordRandom(text, request.context.before),
                )
            }
        val autocorrect = decision.chosen

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
        return RankedPrediction(literal, out, autocorrect, phrase, decision.runnerUp)
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
            var bestBase = 0f
            for (j in 0 until dictionary.wordIdCountAt(node)) {
                val id = dictionary.wordIdAt(node, j)
                if (dictionary.flags(id) and EXCLUDED_FLAGS != 0) continue
                val word = dictionary.word(id)
                if (active && pers.isBlocked(word)) continue
                val base = lm.baseLogProb(id)
                val v = lm.blend(base, word)
                if (v > bestLm) {
                    bestLm = v
                    bestBase = base
                    bestId = id
                }
            }
            if (bestId < 0) continue
            val surface = shaped(dictionary.word(bestId), literal)
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
                    userLm = bestLm - bestBase,
                    correctionBias = if (active) bias(literal, surface) else 0f,
                )
            out.add(
                RankedCandidate(
                    surface,
                    breakdown.total(config.scoring),
                    breakdown,
                    bestId,
                    dictionary.word(bestId),
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
            if (active && pers.isBlocked(lexicon.surface(u))) continue
            val userLm = lm.blend(config.user.wordLogProb, lexicon.surface(u))
            val surface = Casing.apply(lexicon.surface(u), literal)
            val breakdown =
                ScoreBreakdown(
                    spatial = aligner.resultSpatial,
                    lm = userLm,
                    rightLm = rightLm,
                    edits = aligner.resultPenalty,
                    editCount = aligner.resultEdits,
                    completionChars = aligner.resultExtra,
                    source = CandidateSource.UserDictionary,
                    userLm = userLm - config.user.wordLogProb,
                    correctionBias = if (active) bias(literal, surface) else 0f,
                )
            out.add(
                RankedCandidate(
                    surface,
                    breakdown.total(config.scoring),
                    breakdown,
                    -1,
                    lexicon.surface(u),
                )
            )
        }
    }

    /**
     * Puts the contraction for an apostrophe-less, unknown [key] ("dont" -> "don't", "im" -> "I'm")
     * first in [ranked], scored clear of the literal and the previous best so that the autocorrect
     * policy's margins hold whatever the spatial sigma. The policy still decides whether it is
     * applied (field, `autocorrectAllowed`, casing and edit rules). Real words that happen to equal
     * the stripped form ("cant", "wont") never get here: their literal is a dictionary word.
     */
    private fun addContraction(
        key: String,
        text: String,
        literal: RankedCandidate,
        ranked: MutableList<RankedCandidate>,
    ) {
        if (!config.autocorrect.contractions || key.any { Text.isApostrophe(it) }) return
        val entry = contractions.lookup(key) ?: return
        if (active && pers.isBlocked(entry.surface)) return
        val existing = ranked.indexOfFirst { it.wordId == entry.id }
        val base = if (existing >= 0) ranked[existing].breakdown else null
        val breakdown =
            base?.copy(completionChars = 0)
                ?: literal.breakdown.copy(
                    edits = config.edits.insertionPenalty,
                    editCount = 1,
                    source = CandidateSource.Dictionary,
                )
        if (existing >= 0) ranked.removeAt(existing)
        val policy = config.autocorrect
        val score =
            maxOf(literal.score, ranked.firstOrNull()?.score ?: literal.score) +
                autocorrectPolicy.threshold +
                policy.runnerUpMargin +
                1f
        ranked.add(
            0,
            RankedCandidate(
                Casing.apply(entry.surface, text),
                score,
                breakdown,
                entry.id,
                entry.surface,
            ),
        )
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
        if (active && (pers.isBlocked(fixed) || pers.isSuppressed(text, fixed))) return null
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
            if (active) pers.preferredCasing(Text.key(word))?.let { word = it }
            if (sentenceStart && word == word.lowercase()) word = Text.capitalizeFirst(word)
            if (!seen.add(word.lowercase())) continue
            val lp = nextLogProbs[j]
            val userGain = if (lm.hasUser) lp - lm.baseLogProb(nextIds[j]) else 0f
            out.add(
                RankedCandidate(
                        word,
                        lp,
                        ScoreBreakdown(
                            0f,
                            lp,
                            0f,
                            0f,
                            0,
                            0,
                            CandidateSource.Dictionary,
                            userLm = userGain,
                        ),
                        nextIds[j],
                    )
                    .also { it.rank = out.size }
            )
        }
        var phrase: Candidate? = null
        if (request.maxPhraseWords >= 2 && !atDocumentStart && out.isNotEmpty()) {
            val first = out[0]
            if (first.score >= ln(config.phrase.minStepProbability) + gateDelta) {
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
        val minStep = ln(config.phrase.minStepProbability) + gateDelta
        val minTotal = ln(config.phrase.minTotalProbability) + gateDelta
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
        private const val FEATURE_POOL = 16
        private const val TIE_CONTEXT_CHARS = 64
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

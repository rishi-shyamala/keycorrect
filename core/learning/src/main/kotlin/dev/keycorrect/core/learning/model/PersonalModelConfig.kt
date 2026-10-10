package dev.keycorrect.core.learning.model

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

/** Tunables for [SentenceBuffer] (decision D10: on by default, 7-day retention). */
data class SentenceBufferConfig(
    val enabled: Boolean = true,
    val ttlMillis: Long = 7 * DAY,
    val maxSentences: Int = 5_000,
    val maxSentenceWords: Int = 80,
    val maxTotalChars: Int = 1_000_000,
)

/** All tunables of [PersonalModel]; the defaults are the shipped behaviour. */
data class PersonalModelConfig(
    // --- time ---
    /** Half-life of n-gram and correction evidence, in active-clock milliseconds. */
    val halfLifeMillis: Long = 90 * DAY,
    /**
     * The active clock advances by the wall time between two consecutive [PersonalModel.apply]
     * calls, capped at this gap, so weeks of non-use (holiday, phone in a drawer) age evidence by
     * at most one day. Hence "90 days of active use".
     */
    val maxActiveGapMillis: Long = DAY,
    // --- n-gram cache ---
    val maxUnigrams: Int = 20_000,
    val maxBigrams: Int = 50_000,
    val maxTrigrams: Int = 50_000,
    /** Witten-Bell-style interpolation constant: lambda = T / (T + k). */
    val interpolationK: Double = 2.0,
    /** A word needs this much decayed unigram weight before [userLogProb] answers. */
    val minWordEvidence: Double = 2.0,
    /** ... and the cache needs this much total mass. */
    val minTotalEvidence: Double = 20.0,
    /** Anti-poisoning cap on |weight| of one WordUsed update. */
    val maxUsageWeightPerUpdate: Double = 4.0,
    // --- correction memory ---
    val maxCorrectionEntries: Int = 20_000,
    val correctionMaxStep: Double = 2.0,
    val correctionMaxScore: Double = 6.0,
    /** bias = maxBias * tanh(score / saturation). */
    val correctionSaturation: Double = 1.5,
    val maxCorrectionBias: Float = 4f,
    /** Weight of the bucket-free score when blending with the bucket score (0 = ignore). */
    val correctionBackoff: Double = 0.5,
    // --- new-word promotion ---
    /**
     * An unknown word is promoted to the user dictionary once its candidate weight reaches this (a
     * typo the user never fixed rarely survives three sessions)
     */
    val promoteMinCommits: Double = 3.0,
    /** ... in at least this many distinct sessions. */
    val promoteMinSessions: Int = 3,
    /** Fast path for explicit keeps (literal taps weigh 2.0 each): this much weight ... */
    val promoteFastWeight: Double = 4.0,
    /** ... in at least this many sessions promotes without waiting for [promoteMinSessions]. */
    val promoteFastSessions: Int = 2,
    /** Per-session weight needed for the session to count as distinct (voice at 0.3 does not). */
    val promoteSessionMinWeight: Double = 0.5,
    val maxCandidateWords: Int = 5_000,
    val candidateTtlMillis: Long = 60 * DAY,
    // --- keeps of reverted autocorrections (§6.3 rows 6/7) ---
    /**
     * Initial thresholds, changed at runtime by [PersonalModel.updateThresholds]: the typed word is
     * added to the user dictionary after this many keeps ...
     */
    val implicitAddKeeps: Int = 2,
    /**
     * ... and the pair typed -> corrected is never auto-corrected after this many (>= the above).
     */
    val suppressKeeps: Int = 2,
    /** Keep counters retained per kind (word / pair); the least recently kept go first. */
    val maxKeepEntries: Int = 5_000,
    /** Words longer than this (or with whitespace, URLs, no letter) are never added by keeps. */
    val maxKeptWordLength: Int = 40,
    // --- casing ---
    val casingMinObservations: Double = 2.0,
    // --- touch model ---
    val touchMinSamples: Int = 30,
    /** Offsets are clamped to +-this many key sizes. */
    val touchMaxOffset: Float = 0.5f,
    val touchHalfLifeMillis: Long = 180 * DAY,
    /** Effective sample count is capped here so the model stays adaptive. */
    val touchMaxWeight: Double = 1_000.0,
    /** Individual samples are clamped to +-this before use (outlier guard). */
    val touchSampleClamp: Float = 1.0f,
    /** Words per profile before the keyboard-fit report should make recommendations (§6.5). */
    val touchMinProfileWords: Int = 300,
    // --- fusion ranker ---
    val rankerLearningRate: Double = 0.1,
    /** Pull toward the initial (zero) weights per step. */
    val rankerL2: Double = 0.01,
    val rankerMaxWeight: Double = 2.0,
    val rankerMaxScore: Float = 3f,
    val rankerMaxUpdateWeight: Float = 2f,
    val rankerPendingMax: Int = 64,
    val rankerPendingTtlMillis: Long = 10 * 60_000L,
    /**
     * A preference whose features could not be computed is given up on after this many failed
     * attempts (the first try included); each attempt costs up to two decodes.
     */
    val rankerMaxAttempts: Int = 3,
    /** Minimum time between two attempts at the same buffered preference. */
    val rankerRetryGapMillis: Long = 2_000L,
    val checkpointIntervalMillis: Long = HOUR,
    val checkpointRetention: Int = 26,
    // --- adaptive thresholds ---
    val outcomeWindow: Int = 200,
    val targetRevertRate: Double = 0.03,
    /** Per-entry decay of the weighted window (EWMA with an effective length of ~100). */
    val outcomeEwmaDecay: Double = 0.99,
    /** Pseudo-observations at the target rate: few outcomes move the threshold only a little. */
    val outcomePriorWeight: Double = 30.0,
    val thresholdMaxUp: Float = 2f,
    val thresholdMaxDown: Float = 0.5f,
    val thresholdGainUp: Float = 20f,
    val thresholdGainDown: Float = 25f,
    val targetPhraseAcceptance: Double = 0.5,
    val phraseGateMaxUp: Float = 1f,
    val phraseGateMaxDown: Float = 0.5f,
    val phraseGateGain: Float = 4f,
    // --- publishing / journal ---
    /** Publish a snapshot after this many updates ... */
    val publishEveryUpdates: Int = 16,
    /** ... or when this much time passed since the last publish (checked on each apply). */
    val publishMinIntervalMillis: Long = 1_000L,
    val journalRetentionMillis: Long = DAY,
    val maxJournalEntries: Int = 100_000,
    val sentence: SentenceBufferConfig = SentenceBufferConfig(),
) {
    /** Fast settings for tests: publish on every apply. */
    fun eager(): PersonalModelConfig = copy(publishEveryUpdates = 1, publishMinIntervalMillis = 0)
}

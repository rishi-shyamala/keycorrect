package dev.keycorrect.sampleplugin

import android.os.Bundle
import dev.keycorrect.engine.EngineContract
import dev.keycorrect.engine.TextEngineService

/**
 * The sample text-engine plugin: it scores candidates with a tiny built-in bigram model
 * ([BigramScorer]) and does nothing else. It is stateless, so concurrent calls need no locking, and
 * it never stores, logs or sends the text it is given.
 */
class SampleEngineService : TextEngineService() {
    private val scorer = BigramScorer.default

    override fun info(): Bundle =
        Bundle().apply {
            putString(EngineContract.INFO_ID, ENGINE_ID)
            putString(EngineContract.INFO_NAME, ENGINE_NAME)
            putStringArray(
                EngineContract.INFO_CAPABILITIES,
                arrayOf(EngineContract.CAPABILITY_SCORE),
            )
            putStringArray(EngineContract.INFO_LANGUAGES, arrayOf("en"))
            putString(EngineContract.INFO_STATUS, EngineContract.STATUS_READY)
            putString(EngineContract.INFO_MODEL, "toy bigram table, ${scorer.vocabularySize} words")
        }

    /** The sample has no options. */
    override fun configure(options: Bundle) = Unit

    /** The scorer takes microseconds, far below any timeout, so [timeoutMs] is not needed. */
    override fun score(context: String, candidates: Array<String>, timeoutMs: Long): FloatArray =
        scorer.score(context, candidates)

    /** The sample only has the Score capability. */
    override fun generate(prompt: String, maxTokens: Int, timeoutMs: Long): String? = null

    private companion object {
        const val ENGINE_ID = "sample"
        const val ENGINE_NAME = "Sample bigram engine"
    }
}

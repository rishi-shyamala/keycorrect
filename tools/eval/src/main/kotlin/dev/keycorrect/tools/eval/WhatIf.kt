package dev.keycorrect.tools.eval

import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult

/**
 * Experiments that post-process the engine's answer, to put numbers on a decoder change before
 * anyone writes it. They live in the harness only; nothing here touches the core modules.
 */
enum class WhatIf(val flag: String, val description: String) {
    /**
     * Never autocorrect a capitalised word that is not in the dictionary and is not at a sentence
     * start (probably a proper noun: "Sami", "Rima").
     */
    ProtectCapitalizedMidSentence(
        "protect-capitalized",
        "no autocorrect for capitalised out-of-dictionary words mid-sentence",
    ),

    /** Same, also at a sentence start (costs "Teh" -> "The"). */
    ProtectCapitalizedAlways(
        "protect-capitalized-all",
        "no autocorrect for capitalised out-of-dictionary words anywhere",
    );

    companion object {
        fun parse(name: String): WhatIf =
            entries.firstOrNull { it.flag == name }
                ?: throw IllegalArgumentException(
                    "unknown --what-if '$name' (${entries.joinToString { it.flag }})"
                )
    }
}

/** Applies [WhatIf] experiments to every answer of [inner]. */
class WhatIfPredictor(
    private val inner: Predictor,
    private val dictionary: Dictionary,
    private val experiments: Set<WhatIf>,
) : Predictor {
    override fun predict(request: PredictRequest): PredictResult {
        val result = inner.predict(request)
        if (experiments.isEmpty() || result.autocorrect == null) return result
        val literal = request.composing.text
        if (!isCapitalizedWord(literal) || isKnown(literal)) return result
        val always = WhatIf.ProtectCapitalizedAlways in experiments
        val mid = WhatIf.ProtectCapitalizedMidSentence in experiments
        val protect = always || (mid && !atSentenceStart(request.context.before))
        return if (protect) result.copy(autocorrect = null) else result
    }

    private fun isCapitalizedWord(w: String) =
        w.isNotEmpty() && w[0].isUpperCase() && w.any { it.isLowerCase() }

    private fun isKnown(w: String): Boolean {
        val node = dictionary.findNode(w.replace('’', '\'').lowercase())
        return node >= 0 && dictionary.isTerminal(node)
    }

    private fun atSentenceStart(before: String): Boolean {
        val t = before.trimEnd { it.isWhitespace() || it in "\"'“‘([«" }
        if (t.isEmpty() || before.endsWith("\n")) return true
        return t.last() in ".!?…"
    }
}

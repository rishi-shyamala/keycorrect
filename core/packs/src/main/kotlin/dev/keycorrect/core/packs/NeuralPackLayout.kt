package dev.keycorrect.core.packs

/** Where things are in the neural pack (written by `:neuralpack`). */
object NeuralPackLayout {
    const val PACKAGE = "dev.keycorrect.neural"
    const val DEFAULT_MODEL = "models/default.gguf"
    val LIBRARIES = listOf("libkcllama.so")
    val ASSETS = listOf(DEFAULT_MODEL)
}

/** The neural pack as [SignedPackLocator] sees it. */
val NEURAL_PACK_SPEC =
    PackSpec(NeuralPackLayout.PACKAGE, NeuralPackLayout.LIBRARIES, NeuralPackLayout.ASSETS)

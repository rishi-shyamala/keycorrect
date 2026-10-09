package dev.keycorrect.core.dictionary

/** Thrown by [Dictionary.open] when a buffer is not a valid, complete KCD dictionary. */
class DictionaryFormatException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

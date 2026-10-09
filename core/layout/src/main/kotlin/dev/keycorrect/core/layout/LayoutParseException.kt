package dev.keycorrect.core.layout

/**
 * A layout file is malformed or invalid. The message names the layout id and the offending path.
 */
class LayoutParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

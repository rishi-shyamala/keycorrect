package dev.keycorrect.core.engineapi

/**
 * The text around the word being typed (docs/PLAN.md §3.1).
 *
 * [before] ends at the start of the composing word and is trimmed to a bounded length; [after] is
 * the text after the cursor (empty when unknown). Both stop at privacy boundaries: they never
 * contain text from a sensitive field.
 */
data class TypingContext(
    val before: String = "",
    val after: String = "",
    val field: FieldClass = FieldClass.Text,
) {
    companion object {
        /** Upper bound callers should respect when filling [before]. */
        const val MAX_BEFORE_CHARS = 256
        const val MAX_AFTER_CHARS = 64
    }
}

/** Coarse field type, enough for engines to adapt without seeing app details. */
enum class FieldClass {
    Text,
    Email,
    Url,
    PersonName,
    PostalAddress,
}

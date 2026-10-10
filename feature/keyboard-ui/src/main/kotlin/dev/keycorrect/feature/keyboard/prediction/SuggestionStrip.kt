package dev.keycorrect.feature.keyboard.prediction

import androidx.compose.runtime.Immutable
import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.PredictResult

/** What a strip slot does when tapped, and how it is drawn. */
enum class SlotKind {
    /** A plain suggestion: replaces the composing word (or is inserted as next word). */
    Suggestion,

    /** The correction that would be applied on space/punctuation. Drawn bold. */
    Autocorrect,

    /** "Keep what I typed". */
    Literal,

    /** A multi-word completion. Drawn ellipsized with a trailing arrow. */
    Phrase,

    /** A text-expansion shortcut. */
    Shortcut,
}

/**
 * One slot of the strip. [text] is what is displayed, [insert] what a tap inserts (the raw
 * candidate text; shortcuts still carry their variables), [description] the accessibility label.
 */
@Immutable
data class StripSlot(
    val text: String,
    val kind: SlotKind,
    val description: String,
    val insert: String = text,
    /** Drawn bold: the slot holds what will be applied automatically. */
    val bold: Boolean = false,
)

/**
 * The suggestion strip's content: three equal slots, the centre one being the candidate that would
 * be applied. [addWord] is the transient "Add “word” to dictionary" prompt, which takes the strip
 * over while it is non-null; so does [neverSuggest], the inline confirmation shown after a long
 * press on a suggestion ("Never suggest “word”? Yes / Cancel"), which wins over [addWord].
 * Data-class equality is what keeps the strip from recomposing unless something visible changed.
 */
@Immutable
data class StripModel(
    val left: StripSlot? = null,
    val centre: StripSlot? = null,
    val right: StripSlot? = null,
    val addWord: String? = null,
    val neverSuggest: String? = null,
) {
    val isEmpty: Boolean
        get() =
            left == null &&
                centre == null &&
                right == null &&
                addWord == null &&
                neverSuggest == null

    fun withAddWord(word: String?): StripModel = if (word == addWord) this else copy(addWord = word)

    fun withNeverSuggest(word: String?): StripModel =
        if (word == neverSuggest) this else copy(neverSuggest = word)

    companion object {
        val Empty = StripModel()
    }
}

/** Accessibility labels. TODO: move to string resources when strings.xml is open to this module. */
internal object StripLabels {
    fun suggestion(text: String) = "Suggestion: $text"

    fun autocorrect(text: String) = "Autocorrect to $text"

    fun literal(text: String) = "Keep $text as typed"

    fun phrase(text: String) = "Phrase suggestion: $text"

    fun shortcut(text: String) = "Shortcut expansion: $text"

    fun addWord(word: String) = "Add $word to dictionary"

    fun neverSuggestPrompt(word: String) = "Never suggest $word? Yes or cancel"

    fun neverSuggestYes(word: String) = "Yes, never suggest $word"

    fun neverSuggestCancel() = "Cancel, keep suggesting"

    /** The long-click action label of a suggestion slot. */
    fun neverSuggestAction(word: String) = "Never suggest $word"
}

/** Lays a [PredictResult] out into the three strip slots. Pure, so the rules are unit-tested. */
internal object StripBuilder {
    /**
     * @param typed the composing word ("" for next-word predictions).
     * @param pending the candidate applied on space/punctuation (an autocorrect or an
     *   auto-expanding shortcut), or null.
     * @param preview display text for a candidate's raw text (resolves shortcut variables).
     *
     * With a [pending] correction: centre = it, left = the literal, right = the phrase or the next
     * candidate. Otherwise: centre = best, left = second best, right = the phrase or third best;
     * with no candidates at all the typed word itself sits in the centre.
     */
    fun build(
        result: PredictResult,
        typed: String,
        pending: Candidate?,
        preview: (String) -> String,
    ): StripModel {
        val literalText = result.literal?.text ?: typed
        val others =
            result.candidates.filter {
                it.text != pending?.text && it.text != literalText && it.text.isNotEmpty()
            }
        val phrase =
            result.phrase?.takeIf { it.text.isNotEmpty() }?.let { slot(it, false, preview) }

        val centre: StripSlot?
        val left: StripSlot?
        val right: StripSlot?
        if (pending != null) {
            centre = slot(pending, true, preview)
            left = if (typed.isEmpty()) null else literal(literalText)
            right = phrase ?: others.getOrNull(0)?.let { slot(it, false, preview) }
        } else {
            centre =
                others.getOrNull(0)?.let { slot(it, false, preview) }
                    ?: if (typed.isEmpty()) null else literal(literalText)
            left = others.getOrNull(1)?.let { slot(it, false, preview) }
            right = phrase ?: others.getOrNull(2)?.let { slot(it, false, preview) }
        }
        return StripModel(left, centre, right)
    }

    private fun literal(text: String) = StripSlot(text, SlotKind.Literal, StripLabels.literal(text))

    private fun slot(c: Candidate, autocorrect: Boolean, preview: (String) -> String): StripSlot {
        val shown = if (c.source == CandidateSource.Shortcut) preview(c.text) else c.text
        return when {
            c.isPhrase -> StripSlot(shown, SlotKind.Phrase, StripLabels.phrase(shown), c.text)
            c.source == CandidateSource.Shortcut ->
                StripSlot(
                    shown,
                    SlotKind.Shortcut,
                    if (autocorrect) StripLabels.autocorrect(shown)
                    else StripLabels.shortcut(shown),
                    c.text,
                    bold = autocorrect,
                )
            autocorrect ->
                StripSlot(
                    shown,
                    SlotKind.Autocorrect,
                    StripLabels.autocorrect(shown),
                    c.text,
                    bold = true,
                )
            else -> StripSlot(shown, SlotKind.Suggestion, StripLabels.suggestion(shown), c.text)
        }
    }
}

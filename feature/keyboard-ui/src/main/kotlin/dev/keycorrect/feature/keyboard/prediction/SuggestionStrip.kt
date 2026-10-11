package dev.keycorrect.feature.keyboard.prediction

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.feature.keyboard.R

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
 * candidate text; shortcuts still carry their variables); [labelRes] gives the accessibility label.
 */
@Immutable
data class StripSlot(
    val text: String,
    val kind: SlotKind,
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

/**
 * The accessibility label of this slot, a string resource taking [StripSlot.text]. A shortcut that
 * would be applied on space reads as an autocorrection.
 */
@StringRes
internal fun StripSlot.labelRes(): Int =
    when (kind) {
        SlotKind.Suggestion -> R.string.strip_suggestion
        SlotKind.Autocorrect -> R.string.strip_autocorrect
        SlotKind.Literal -> R.string.strip_literal
        SlotKind.Phrase -> R.string.strip_phrase
        SlotKind.Shortcut -> if (bold) R.string.strip_autocorrect else R.string.strip_shortcut
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

    private fun literal(text: String) = StripSlot(text, SlotKind.Literal)

    private fun slot(c: Candidate, autocorrect: Boolean, preview: (String) -> String): StripSlot {
        val shown = if (c.source == CandidateSource.Shortcut) preview(c.text) else c.text
        return when {
            c.isPhrase -> StripSlot(shown, SlotKind.Phrase, c.text)
            c.source == CandidateSource.Shortcut ->
                StripSlot(
                    shown,
                    SlotKind.Shortcut,
                    c.text,
                    bold = autocorrect,
                )
            autocorrect ->
                StripSlot(
                    shown,
                    SlotKind.Autocorrect,
                    c.text,
                    bold = true,
                )
            else -> StripSlot(shown, SlotKind.Suggestion, c.text)
        }
    }
}

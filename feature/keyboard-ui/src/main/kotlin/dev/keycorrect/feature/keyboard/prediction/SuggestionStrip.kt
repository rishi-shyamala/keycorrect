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

    /**
     * An emoji for the word being typed (an exact CLDR keyword). A tap replaces the word with it;
     * it is never the autocorrect and learning is told nothing about it.
     */
    Emoji,
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
    /** What the accessibility label names: [text], or the emoji's CLDR name for an emoji slot. */
    val label: String = text,
    /** A literal whose tap also adds the word to the dictionary ("+ word"). */
    val addsWord: Boolean = false,
)

/**
 * The suggestion strip's content: three slots, the centre one being the candidate that would be
 * applied. [neverSuggest], the inline confirmation shown after a long press on a suggestion ("Never
 * suggest “word”? Yes / Cancel"), takes the strip over while it is non-null. Data-class equality is
 * what keeps the strip from recomposing unless something visible changed.
 */
@Immutable
data class StripModel(
    val left: StripSlot? = null,
    val centre: StripSlot? = null,
    val right: StripSlot? = null,
    val neverSuggest: String? = null,
) {
    val isEmpty: Boolean
        get() = left == null && centre == null && right == null && neverSuggest == null

    fun withNeverSuggest(word: String?): StripModel =
        if (word == neverSuggest) this else copy(neverSuggest = word)

    companion object {
        val Empty = StripModel()
    }
}

/**
 * The accessibility label of this slot, a string resource taking [StripSlot.label]. A shortcut that
 * would be applied on space reads as an autocorrection.
 */
@StringRes
internal fun StripSlot.labelRes(): Int =
    when (kind) {
        SlotKind.Suggestion -> R.string.strip_suggestion
        SlotKind.Autocorrect -> R.string.strip_autocorrect
        SlotKind.Literal -> if (addsWord) R.string.strip_add_word else R.string.strip_literal
        SlotKind.Phrase -> R.string.strip_phrase
        SlotKind.Shortcut -> if (bold) R.string.strip_autocorrect else R.string.strip_shortcut
        SlotKind.Emoji -> R.string.strip_emoji
    }

/** Lays a [PredictResult] out into the three strip slots. Pure, so the rules are unit-tested. */
/** An emoji for the strip: the [form] to show and insert (the user's skin tone), and its [name]. */
internal class EmojiSuggestion(val form: String, val name: String)

private const val ADD_WORD_MARK = "+ "

internal object StripBuilder {
    /**
     * @param typed the composing word ("" for next-word predictions).
     * @param pending the candidate applied on space/punctuation (an autocorrect or an
     *   auto-expanding shortcut), or null.
     * @param emoji the emoji a strong keyword match suggests for [typed], or null. It takes the
     *   right slot, whatever was there, and is never the pending correction.
     * @param unknownWord [typed] is in no dictionary and could be added: its slot says so.
     * @param preview display text for a candidate's raw text (resolves shortcut variables).
     *
     * A shortcut for [typed] always takes the centre. Otherwise, with a [pending] correction:
     * centre = it, left = the literal, right = the phrase or the next candidate. Without: centre =
     * best, left = second best (the literal when [unknownWord]), right = the phrase or third best;
     * with no candidates at all the typed word itself sits in the centre.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod") // One table of who gets which slot.
    fun build(
        result: PredictResult,
        typed: String,
        pending: Candidate?,
        emoji: EmojiSuggestion? = null,
        unknownWord: Boolean = false,
        preview: (String) -> String,
    ): StripModel {
        val literalText = result.literal?.text ?: typed
        val shortcut = result.candidates.firstOrNull { it.source == CandidateSource.Shortcut }
        val others =
            result.candidates.filter {
                it !== shortcut &&
                    it.text != pending?.text &&
                    it.text != literalText &&
                    it.text.isNotEmpty()
            }
        fun other(i: Int) = others.getOrNull(i)?.let { slot(it, false, preview) }
        val phrase =
            result.phrase?.takeIf { it.text.isNotEmpty() }?.let { slot(it, false, preview) }
        val typedWord = typedSlot(typed, literalText, unknownWord)
        val pendingSlot = pending?.let { slot(it, true, preview) }
        val best = other(0)
        val third = phrase ?: other(2)
        val (left, centre, right) =
            when {
                // A shortcut always takes the middle; it expands only if tapped.
                shortcut != null && shortcut.text != pending?.text ->
                    Triple(
                        pendingSlot ?: typedWord,
                        slot(shortcut, false, preview),
                        phrase ?: (if (pending != null) typedWord else best),
                    )
                pending != null -> Triple(typedWord, pendingSlot, phrase ?: best)
                // An unknown word stays on the strip, where a tap adds it to the dictionary, unless
                // a suggestion continues it: then it is probably a word still being typed.
                best != null && unknownWord && !continues(others, literalText) ->
                    Triple(typedWord, best, third)
                else -> Triple(other(1), best ?: typedWord, third)
            }
        val emojiSlot =
            if (typed.isEmpty() || emoji == null) null
            else StripSlot(emoji.form, SlotKind.Emoji, label = emoji.name)
        return StripModel(left, centre, emojiSlot ?: right)
    }

    private fun continues(candidates: List<Candidate>, word: String) = candidates.any {
        it.text.startsWith(word, ignoreCase = true)
    }

    /** The slot of what was typed, if anything; "+" says a tap adds it. */
    private fun typedSlot(typed: String, text: String, unknownWord: Boolean): StripSlot? =
        when {
            typed.isEmpty() -> null
            unknownWord ->
                StripSlot(
                    "$ADD_WORD_MARK$text",
                    SlotKind.Literal,
                    insert = text,
                    label = text,
                    addsWord = true,
                )
            else -> StripSlot(text, SlotKind.Literal)
        }

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

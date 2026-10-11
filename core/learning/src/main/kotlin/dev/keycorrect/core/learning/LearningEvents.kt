package dev.keycorrect.core.learning

import dev.keycorrect.core.engineapi.FieldClass

/**
 * What the keyboard observed, emitted after the privacy gate (docs/PLAN.md §6.2–6.3): nothing is
 * ever emitted for sensitive, no-learning or incognito fields, nor for pasted, autofilled or
 * shortcut-expanded text. Words are as committed (surface form); context is the last ≤ 2 committed
 * words before the word, normalised (apostrophes folded to ', case kept).
 */
sealed interface LearningEvent {
    val timeMillis: Long
    val profile: InputProfile
}

/** Where the typing happened; touch statistics and some counts are kept per profile (§6.5). */
data class InputProfile(
    val device: DeviceClass,
    val landscape: Boolean,
    /** Layout id plus geometry, e.g. "qwerty@54dp"; touch offsets are only comparable within it. */
    val layoutKey: String,
    val field: FieldClass,
) {
    /** Key for per-profile touch statistics. */
    val touchKey: String
        get() = "${device.name}/${if (landscape) "land" else "port"}/$layoutKey"
}

enum class DeviceClass {
    Phone,
    Tablet,
    HardwareKeyboard,
}

/**
 * A touch for one typed character, relative to the centre of the INTENDED key.
 *
 * Contract (emitter side): the intended key is the key of the char at the same position of the
 * committed word, not the key that registered the tap. [WordCommitted.touches] is filled only for
 * substitution-aligned words (typed and committed have the same length and differ at most by
 * substituted chars), so a tap that landed on a neighbouring key still yields a sample (this is
 * what keeps learned offsets unbiased; using only taps that hit the intended key would shrink them
 * toward zero). Samples with |dx| or |dy| > 1.2 are dropped by the emitter: such a tap was a
 * different letter, not an offset. For other words the list is empty or all null.
 */
data class TouchSample(
    /** The intended key's char (lowercase): `committed[i]` for the sample at position i. */
    val char: Char,
    /** Offset from the intended key's centre in key widths (x) and key heights (y). */
    val dx: Float,
    val dy: Float,
)

/** A suggestion that was visible while the word was being typed. */
data class ShownSuggestion(
    val text: String,
    /** 0 = the slot that would be applied (centre), 1 and 2 the others. */
    val rank: Int,
    /** How many characters of the word had been typed when it first appeared. */
    val shownAtChars: Int,
    /** How long it stayed visible before the word was finished. */
    val visibleMillis: Long,
)

enum class CommitMethod {
    /** Typed out and finished with a separator; no correction applied. */
    Typed,
    /** Autocorrect replaced the literal on a separator. */
    Autocorrected,
    /** User tapped a suggestion (see [WordCommitted.pickedRank]). */
    SuggestionPicked,
    /** User tapped the literal ("keep what I typed"). */
    LiteralPicked,
    /** Next-word prediction tapped with nothing typed. */
    PredictionPicked,
    Gesture,
    Voice,
}

/** A word was committed. One per word; the core training signal (§6.3 rows 1–5, 8, 12–16). */
data class WordCommitted(
    override val timeMillis: Long,
    override val profile: InputProfile,
    val context: List<String>,
    /** What the user typed (the literal); empty for [CommitMethod.PredictionPicked]. */
    val typed: String,
    val committed: String,
    val method: CommitMethod,
    val pickedRank: Int? = null,
    /** Suggestions visible while typing, most recent snapshot per text. */
    val shown: List<ShownSuggestion> = emptyList(),
    /**
     * One per typed char (null where the char didn't come from a tap or the sample was dropped),
     * relative to the intended key; see [TouchSample]. Empty unless typed and committed are
     * substitution-aligned.
     */
    val touches: List<TouchSample?> = emptyList(),
    val sentenceInitial: Boolean = false,
    /** True when [committed] (case-insensitively) is in the main dictionary or user words. */
    val committedIsKnown: Boolean = true,
    /** True when [typed] (case-insensitively) is in the main dictionary or user words. */
    val typedIsKnown: Boolean = true,
    /** Autocorrect picked between near-tied candidates (§6.3 row 22); the runner-up. */
    val tieRunnerUp: String? = null,
    /** Session id: a new session starts when the keyboard is shown in a new field. */
    val sessionId: Long = 0,
) : LearningEvent

/** How an applied autocorrection was undone (§6.3 rows 6, 7, 22, 23). */
enum class RevertKind {
    /** One backspace straight after the correction. */
    ImmediateBackspace,
    /** The corrected word was deleted and the original literal typed again. */
    DeletedAndRetyped,
    /**
     * The corrected word was later edited into something else ([AutocorrectReverted.replacement]).
     */
    EditedLater,
    /** The user tapped the tie runner-up / another suggestion to replace the correction. */
    SwitchedToSuggestion,
}

data class AutocorrectReverted(
    override val timeMillis: Long,
    override val profile: InputProfile,
    val context: List<String>,
    val typed: String,
    val corrected: String,
    val kind: RevertKind,
    /**
     * The word the user ended up with (equals [typed] for ImmediateBackspace/DeletedAndRetyped).
     */
    val replacement: String,
) : LearningEvent

/** A committed word (not an autocorrection) was later replaced (§6.3 rows 9, 10). */
data class WordReplaced(
    override val timeMillis: Long,
    override val profile: InputProfile,
    val context: List<String>,
    val original: String,
    val replacement: String,
    /** True when the original had just been inserted by tapping a suggestion (mis-tap, row 9). */
    val originalWasPicked: Boolean,
    val millisAfterCommit: Long,
) : LearningEvent

/** Phrase chip interactions (§6.3 rows 18–20). */
data class PhraseOutcome(
    override val timeMillis: Long,
    override val profile: InputProfile,
    val context: List<String>,
    val phrase: List<String>,
    /** Number of leading phrase words the user kept. */
    val acceptedWords: Int,
    /** The word typed instead of phrase[acceptedWords], if any. */
    val divergedWith: String?,
) : LearningEvent

/** Explicit user actions. */
data class UserAction(
    override val timeMillis: Long,
    override val profile: InputProfile,
    val kind: Kind,
    val word: String,
) : LearningEvent {
    enum class Kind {
        AddToDictionary,
        NeverSuggest,
    }
}

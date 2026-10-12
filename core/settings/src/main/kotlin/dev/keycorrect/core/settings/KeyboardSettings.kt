package dev.keycorrect.core.settings

/** Spacebar cursor speed: [stepDp] is the finger travel per character at a slow drag. */
enum class CursorSpeed(val stepDp: Float) {
    Slow(SLOW_STEP_DP),
    Normal(NORMAL_STEP_DP),
    Fast(FAST_STEP_DP),
}

/** Default skin tone for emoji that have tone variants; [None] is the plain (yellow) form. */
enum class SkinTone {
    None,
    Light,
    MediumLight,
    Medium,
    MediumDark,
    Dark,
}

/** How long unpinned clipboard history items are kept. */
enum class ClipRetention(val millis: Long) {
    Hour(HOUR_MS),
    Day(DAY_MS),
    Week(WEEK_MS),
    Month(MONTH_MS),
}

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L
private const val WEEK_MS = 604_800_000L
private const val MONTH_MS = 2_592_000_000L // 30 days

private const val SLOW_STEP_DP = 18f
private const val NORMAL_STEP_DP = 12f
private const val FAST_STEP_DP = 7f

/** User-tunable keyboard behaviour. Every field has a safe default; see [sanitized]. */
data class KeyboardSettings(
    /** Key height in dp, [KEY_HEIGHT_DP_RANGE]. */
    val keyHeightDp: Int = 54,
    val numberRow: Boolean = false,
    val keyPreview: Boolean = true,
    /** Long-press threshold in milliseconds, [LONG_PRESS_DELAY_MS_RANGE]. */
    val longPressDelayMs: Int = 300,
    val autoCapitalize: Boolean = true,
    val hapticsEnabled: Boolean = true,
    /** 0 = system keyboard-tap haptic, otherwise a custom one-shot of 1..100 ms. */
    val hapticDurationMs: Int = 0,
    /** 0 = device default amplitude, otherwise 1..255. */
    val hapticStrength: Int = 0,
    val soundEnabled: Boolean = false,
    /** Key-click volume, [SOUND_VOLUME_RANGE]. */
    val soundVolume: Float = 0.5f,
    /** Show the autofill service's (password manager) inline suggestions in the strip. */
    val inlineAutofill: Boolean = true,
    /** Show word suggestions in the strip. */
    val suggestionsEnabled: Boolean = true,
    /** Replace likely typos on space/punctuation (one backspace undoes). */
    val autocorrectEnabled: Boolean = true,
    /** Suggest the next word after a word is finished. */
    val nextWordPredictions: Boolean = true,
    /** Offer multi-word completions (docs/PLAN.md §3.2). */
    val phraseCompletion: Boolean = true,
    /** Insert a space after picking a suggestion. */
    val autoSpaceAfterPick: Boolean = true,
    /** Learn from typing (docs/PLAN.md §6). Off = base models only, nothing recorded. */
    val learningEnabled: Boolean = true,
    /**
     * Keep recent non-sensitive sentences (7 days, encrypted) for on-device model training
     * (decision D10).
     */
    val sentenceBufferEnabled: Boolean = true,
    /** Pause all learning and history until turned off. */
    val incognito: Boolean = false,
    /** Undos of an autocorrection of a word before it's added to the user dictionary. */
    val implicitAddReverts: Int = 2,
    /** Undos before that word is never auto-corrected again; always >= [implicitAddReverts]. */
    val suppressCorrectionReverts: Int = 2,
    /**
     * Non-dictionary word with near-tied candidates: autocorrect to the best guess (§6.3 row 22).
     */
    val nonWordTieRule: Boolean = true,
    /** Distinct vibration when a word is auto-corrected. */
    val hapticOnAutocorrect: Boolean = true,
    /** Distinct vibration when a finished word is not in any dictionary and wasn't corrected. */
    val hapticOnUnknownWord: Boolean = true,
    /** Dragging on the spacebar moves the cursor; hold then drag selects (docs/PHASE4.md). */
    val spacebarCursor: Boolean = true,
    /** How far a spacebar drag moves per character. */
    val cursorSpeed: CursorSpeed = CursorSpeed.Normal,
    /** Swiping left on backspace selects whole words and release deletes them. */
    val backspaceSwipe: Boolean = true,
    /** An emoji in the strip for a word that strongly matches one ("pizza" -> 🍕; PHASE4 P4-5). */
    val emojiSuggestions: Boolean = true,
    /** The skin tone emoji are inserted in unless one was chosen for that emoji. */
    val emojiSkinTone: SkinTone = SkinTone.None,
    /**
     * Clipboard history (PHASE4 PR C): copied text and images, encrypted, while we're the keyboard.
     */
    val clipboardHistory: Boolean = true,
    /** Unpinned clips kept at most (pinned ones are kept regardless). */
    val clipboardMaxItems: Int = 30,
    val clipboardRetention: ClipRetention = ClipRetention.Week,
    /** Copied images are kept too (encrypted, 20 MB in all). */
    val clipboardImages: Boolean = true,
) {
    /** Returns a copy with every value clamped into its valid range. */
    fun sanitized(): KeyboardSettings =
        copy(
            clipboardMaxItems = clipboardMaxItems.coerceIn(CLIPBOARD_MAX_ITEMS_RANGE),
            keyHeightDp = keyHeightDp.coerceIn(KEY_HEIGHT_DP_RANGE),
            longPressDelayMs = longPressDelayMs.coerceIn(LONG_PRESS_DELAY_MS_RANGE),
            hapticDurationMs = hapticDurationMs.coerceIn(HAPTIC_DURATION_MS_RANGE),
            hapticStrength = hapticStrength.coerceIn(HAPTIC_STRENGTH_RANGE),
            implicitAddReverts = implicitAddReverts.coerceIn(REVERTS_RANGE),
            suppressCorrectionReverts =
                suppressCorrectionReverts
                    .coerceIn(REVERTS_RANGE)
                    .coerceAtLeast(implicitAddReverts.coerceIn(REVERTS_RANGE)),
            soundVolume =
                if (soundVolume.isNaN()) DEFAULT_SOUND_VOLUME
                else soundVolume.coerceIn(SOUND_VOLUME_RANGE),
        )

    companion object {
        val KEY_HEIGHT_DP_RANGE = 40..80
        val LONG_PRESS_DELAY_MS_RANGE = 150..800
        val HAPTIC_DURATION_MS_RANGE = 0..100
        val HAPTIC_STRENGTH_RANGE = 0..255
        val SOUND_VOLUME_RANGE = 0f..1f
        val REVERTS_RANGE = 1..5
        val CLIPBOARD_MAX_ITEMS_RANGE = 5..100
        private const val DEFAULT_SOUND_VOLUME = 0.5f
    }
}

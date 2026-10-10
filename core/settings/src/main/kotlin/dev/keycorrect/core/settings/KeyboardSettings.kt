package dev.keycorrect.core.settings

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
) {
    /** Returns a copy with every value clamped into its valid range. */
    fun sanitized(): KeyboardSettings =
        copy(
            keyHeightDp = keyHeightDp.coerceIn(KEY_HEIGHT_DP_RANGE),
            longPressDelayMs = longPressDelayMs.coerceIn(LONG_PRESS_DELAY_MS_RANGE),
            hapticDurationMs = hapticDurationMs.coerceIn(HAPTIC_DURATION_MS_RANGE),
            hapticStrength = hapticStrength.coerceIn(HAPTIC_STRENGTH_RANGE),
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
        private const val DEFAULT_SOUND_VOLUME = 0.5f
    }
}

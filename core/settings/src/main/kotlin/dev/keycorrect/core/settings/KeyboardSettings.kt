package dev.keycorrect.core.settings

/** How fast the spacebar joystick moves the cursor: a multiplier on its speeds (docs/PHASE4.md). */
enum class CursorSpeed(val joystickScale: Float) {
    Slow(SLOW_SCALE),
    Normal(NORMAL_SCALE),
    Fast(FAST_SCALE),
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

/** Which speech recognizer voice typing uses (docs/PHASE6.md). */
enum class VoiceEngineChoice {
    /**
     * The built-in model from the KeyCorrect voice pack, on this device. Never falls back to the
     * phone's recognizer: without a usable pack dictation says so.
     */
    BuiltIn,

    /**
     * The phone's own speech recognizer (Google, FUTO Voice Input, ...), an explicit opt-in: it may
     * send audio to its provider's servers. Never used in private fields (incognito, no learning).
     */
    PhoneRecognizer;

    companion object {
        /** The stored name; the old names (before the engine choice was made explicit) migrate. */
        fun parse(name: String?): VoiceEngineChoice? =
            when (name) {
                "Automatic" -> BuiltIn
                "System" -> PhoneRecognizer
                else -> entries.firstOrNull { it.name == name }
            }
    }
}

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L
private const val WEEK_MS = 604_800_000L
private const val MONTH_MS = 2_592_000_000L // 30 days

private const val SLOW_SCALE = 0.6f
private const val NORMAL_SCALE = 1f
private const val FAST_SCALE = 1.6f

/** User-tunable keyboard behaviour. Every field has a safe default; see [sanitized]. */
data class KeyboardSettings(
    /** Key height in dp, [KEY_HEIGHT_DP_RANGE]. */
    val keyHeightDp: Int = 54,
    /**
     * Width of the bottom row's emoji, comma and period keys as a fraction of a letter key,
     * [BOTTOM_KEY_WIDTH_RANGE]; the spare width goes to the space bar.
     */
    val bottomKeyWidth: Float = DEFAULT_BOTTOM_KEY_WIDTH,
    /** Width of the bottom row's ?123/ABC and Enter keys, [MODE_ENTER_KEY_WIDTH_RANGE]. */
    val modeEnterKeyWidth: Float = DEFAULT_MODE_ENTER_KEY_WIDTH,
    /**
     * Padding around the whole keyboard in dp, [EDGE_PADDING_DP_RANGE]; the default is the old one.
     */
    val edgePaddingDp: Int = DEFAULT_EDGE_PADDING_DP,
    /** Space between neighbouring keys in dp, [KEY_GAP_DP_RANGE]; 0 = keys touch. */
    val keyGapDp: Int = DEFAULT_KEY_GAP_DP,
    val numberRow: Boolean = true,
    /**
     * Each letter key's corner hint (and first popup entry) is the symbol at its place on page 1.
     */
    val symbolHints: Boolean = true,
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
    /**
     * Length of each of the two pulses of the autocorrect haptic in ms, [HAPTIC_PULSE_MS_RANGE].
     */
    val autocorrectPulseMs: Int = DEFAULT_HAPTIC_PULSE_MS,
    /** Length of each of the three pulses of the unknown-word haptic in ms, same range. */
    val unknownWordPulseMs: Int = DEFAULT_HAPTIC_PULSE_MS,
    /**
     * Dragging on the spacebar moves the cursor (right half) or selects (left half);
     * docs/PHASE4.md.
     */
    val spacebarCursor: Boolean = true,
    /** Gesture (swipe) typing on letter keys (docs/PHASE5.md). */
    val gestureTyping: Boolean = true,
    /** Draw the swipe's trail under the finger. */
    val gestureTrail: Boolean = true,
    /** How fast the spacebar joystick moves the cursor. */
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
    /** Voice typing: long-press the comma (docs/PHASE6.md). Never offered in password fields. */
    val voiceEnabled: Boolean = true,
    val voiceEngine: VoiceEngineChoice = VoiceEngineChoice.BuiltIn,
    /** Ask the recognizer for punctuation and capitalisation. */
    val voiceAutoPunctuation: Boolean = true,
    /**
     * The longest stretch spoken without a pause that is decoded in one go, in ms
     * ([VOICE_PHRASE_MS_RANGE]); the ring in the listening bar fills towards it. The built-in model
     * fails above about 9.25 s, so the range stops well before.
     */
    val voicePhraseMs: Int = DEFAULT_VOICE_PHRASE_MS,
    /**
     * How long a pause must last before the phrase before it is decoded, in ms
     * ([VOICE_PAUSE_MS_RANGE], and never more than half of [voicePhraseMs]).
     */
    val voicePauseMs: Int = DEFAULT_VOICE_PAUSE_MS,
    /**
     * How often the words are updated while speaking, in ms: [VOICE_LIVE_AUTO] (as often as the
     * phone manages), [VOICE_LIVE_OFF] (only after each pause) or a fixed interval in
     * [VOICE_LIVE_MS_RANGE].
     */
    val voiceLiveMs: Int = VOICE_LIVE_AUTO,
    /** Stop dictating after this many seconds without speech; [VOICE_AUTO_STOP_OFF] never. */
    val voiceAutoStopSeconds: Int = DEFAULT_VOICE_AUTO_STOP_SECONDS,
    /** Let an on-device language model reorder the suggestions when one is installed (PHASE7). */
    val neuralRerank: Boolean = true,
    /** Offer whole-phrase completions from the on-device language model (PHASE7; off). */
    val neuralPhrases: Boolean = false,
    /** How much the language model's opinion counts in the reranking, [LM_STRENGTH_RANGE]. */
    val lmStrength: Float = DEFAULT_LM_STRENGTH,
    /**
     * The imported model the language model runs (the id of an imported model, at most
     * [NEURAL_MODEL_MAX_LENGTH] characters); empty = the neural pack's default. Never carried by
     * backup or transfer.
     */
    val neuralModel: String = "",
    /**
     * Which engine answers: [ENGINE_BUILTIN], or an approved plugin's flattened component name
     * (at most [ENGINE_ID_MAX_LENGTH] characters). Never carried by backup or transfer.
     */
    val neuralEngine: String = ENGINE_BUILTIN,
    /**
     * The plugins the user approved, as `<flattened component>|<SHA-256 of the signing
     * certificate>`: a plugin counts as approved only while both still match. At most
     * [APPROVED_ENGINES_MAX] entries. Never carried by backup or transfer.
     */
    val approvedEngines: Set<String> = emptySet(),
) {
    /** Returns a copy with every value clamped into its valid range. */
    fun sanitized(): KeyboardSettings =
        copy(
            clipboardMaxItems = clipboardMaxItems.coerceIn(CLIPBOARD_MAX_ITEMS_RANGE),
            keyHeightDp = keyHeightDp.coerceIn(KEY_HEIGHT_DP_RANGE),
            bottomKeyWidth =
                if (bottomKeyWidth.isNaN()) DEFAULT_BOTTOM_KEY_WIDTH
                else bottomKeyWidth.coerceIn(BOTTOM_KEY_WIDTH_RANGE),
            modeEnterKeyWidth =
                if (modeEnterKeyWidth.isNaN()) DEFAULT_MODE_ENTER_KEY_WIDTH
                else modeEnterKeyWidth.coerceIn(MODE_ENTER_KEY_WIDTH_RANGE),
            edgePaddingDp = edgePaddingDp.coerceIn(EDGE_PADDING_DP_RANGE),
            keyGapDp = keyGapDp.coerceIn(KEY_GAP_DP_RANGE),
            longPressDelayMs = longPressDelayMs.coerceIn(LONG_PRESS_DELAY_MS_RANGE),
            hapticDurationMs = hapticDurationMs.coerceIn(HAPTIC_DURATION_MS_RANGE),
            hapticStrength = hapticStrength.coerceIn(HAPTIC_STRENGTH_RANGE),
            autocorrectPulseMs = autocorrectPulseMs.coerceIn(HAPTIC_PULSE_MS_RANGE),
            unknownWordPulseMs = unknownWordPulseMs.coerceIn(HAPTIC_PULSE_MS_RANGE),
            implicitAddReverts = implicitAddReverts.coerceIn(REVERTS_RANGE),
            suppressCorrectionReverts =
                suppressCorrectionReverts
                    .coerceIn(REVERTS_RANGE)
                    .coerceAtLeast(implicitAddReverts.coerceIn(REVERTS_RANGE)),
            soundVolume =
                if (soundVolume.isNaN()) DEFAULT_SOUND_VOLUME
                else soundVolume.coerceIn(SOUND_VOLUME_RANGE),
            voicePhraseMs = voicePhraseMs.coerceIn(VOICE_PHRASE_MS_RANGE),
            // Never more than half the phrase: a longer pause would not fit in it.
            voicePauseMs =
                voicePauseMs
                    .coerceIn(VOICE_PAUSE_MS_RANGE)
                    .coerceAtMost(voicePhraseMs.coerceIn(VOICE_PHRASE_MS_RANGE) / 2),
            voiceLiveMs =
                when {
                    voiceLiveMs < 0 -> VOICE_LIVE_OFF
                    voiceLiveMs == VOICE_LIVE_AUTO -> VOICE_LIVE_AUTO
                    else -> voiceLiveMs.coerceIn(VOICE_LIVE_MS_RANGE)
                },
            voiceAutoStopSeconds =
                when {
                    voiceAutoStopSeconds <= VOICE_AUTO_STOP_OFF -> VOICE_AUTO_STOP_OFF
                    else -> voiceAutoStopSeconds.coerceIn(VOICE_AUTO_STOP_SECONDS_RANGE)
                },
            neuralModel = neuralModel.take(NEURAL_MODEL_MAX_LENGTH),
            neuralEngine = neuralEngine.take(ENGINE_ID_MAX_LENGTH).ifEmpty { ENGINE_BUILTIN },
            approvedEngines =
                approvedEngines
                    .filter { it.length <= APPROVED_ENTRY_MAX_LENGTH }
                    .sorted()
                    .take(APPROVED_ENGINES_MAX)
                    .toSet(),
            lmStrength =
                if (lmStrength.isNaN()) DEFAULT_LM_STRENGTH
                else lmStrength.coerceIn(LM_STRENGTH_RANGE),
        )

    companion object {
        val KEY_HEIGHT_DP_RANGE = 40..80
        val BOTTOM_KEY_WIDTH_RANGE = 0.30f..1.50f
        val MODE_ENTER_KEY_WIDTH_RANGE = 0.75f..1.50f
        val EDGE_PADDING_DP_RANGE = 0..4
        val KEY_GAP_DP_RANGE = 0..6
        const val DEFAULT_BOTTOM_KEY_WIDTH = 0.75f
        const val DEFAULT_MODE_ENTER_KEY_WIDTH = 1.15f
        const val DEFAULT_EDGE_PADDING_DP = 4
        const val DEFAULT_KEY_GAP_DP = 6
        val LONG_PRESS_DELAY_MS_RANGE = 150..800
        val HAPTIC_DURATION_MS_RANGE = 0..100
        val HAPTIC_STRENGTH_RANGE = 0..255
        val HAPTIC_PULSE_MS_RANGE = 5..60
        const val DEFAULT_HAPTIC_PULSE_MS = 20
        val SOUND_VOLUME_RANGE = 0f..1f
        val REVERTS_RANGE = 1..5
        val CLIPBOARD_MAX_ITEMS_RANGE = 5..100

        /** 3 s to 8.5 s: Moonshine v2 tiny starts failing at 9.25 s (docs/PHASE6.md). */
        val VOICE_PHRASE_MS_RANGE = 3000..8000
        const val DEFAULT_VOICE_PHRASE_MS = 7000
        val VOICE_PAUSE_MS_RANGE = 300..1500
        const val DEFAULT_VOICE_PAUSE_MS = 500
        val VOICE_LIVE_MS_RANGE = 500..2000
        const val VOICE_LIVE_AUTO = 0
        const val VOICE_LIVE_OFF = -1
        val VOICE_AUTO_STOP_SECONDS_RANGE = 3..30
        const val VOICE_AUTO_STOP_OFF = 0
        const val DEFAULT_VOICE_AUTO_STOP_SECONDS = 10
        val LM_STRENGTH_RANGE = 0f..1f

        /** The longest stored model id; a longer value is cut. */
        const val NEURAL_MODEL_MAX_LENGTH = 64

        /** The [neuralEngine] value for the built-in engine. */
        const val ENGINE_BUILTIN = "builtin"
        const val ENGINE_ID_MAX_LENGTH = 256
        const val APPROVED_ENTRY_MAX_LENGTH = 400
        const val APPROVED_ENGINES_MAX = 16
        const val DEFAULT_LM_STRENGTH = 0.5f
        private const val DEFAULT_SOUND_VOLUME = 0.5f
    }
}

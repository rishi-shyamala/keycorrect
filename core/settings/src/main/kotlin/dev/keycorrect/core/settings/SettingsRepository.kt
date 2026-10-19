package dev.keycorrect.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Reads and writes [KeyboardSettings] in a Preferences [DataStore]. */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    /** Current settings, always sanitized; falls back to defaults if the store is unreadable. */
    val settings: Flow<KeyboardSettings> =
        dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { it.toSettings().sanitized() }
            .distinctUntilChanged()

    /** Atomically applies [transform] to the stored settings and writes the sanitized result. */
    suspend fun update(transform: (KeyboardSettings) -> KeyboardSettings) {
        dataStore.edit { prefs ->
            prefs.write(transform(prefs.toSettings().sanitized()).sanitized())
        }
    }

    /**
     * Every setting by its stored key, with its current (sanitized) value: Boolean, Int, Float or
     * String, or a Set of strings. Settings that were never changed are included at their defaults,
     * so a backup does not depend on this build's defaults.
     */
    suspend fun exportValues(): Map<String, Any> {
        val prefs =
            dataStore.data
                .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
                .first()
        return prefs.toSettings().sanitized().toValues()
    }

    /**
     * Writes [values] (as produced by [exportValues], possibly by another build) in one atomic
     * edit. Keys this build does not know and values of the wrong type are ignored; the rest is
     * sanitized. With [replace] every setting not in [values] returns to its default, otherwise it
     * keeps its current value. The keys in [keepLocal] always keep this device's current value (the
     * screen-tied sizes an import skips).
     */
    suspend fun applyValues(
        values: Map<String, Any>,
        replace: Boolean = false,
        keepLocal: Set<String> = emptySet(),
    ) {
        dataStore.edit { prefs ->
            val current = prefs.toSettings().sanitized()
            val target = mutablePreferencesOf()
            target.write(if (replace) KeyboardSettings() else current)
            for ((name, value) in values) {
                if (name !in keepLocal) target.setIfKnown(name, value)
            }
            val merged = target.toSettings().sanitized().toValues().toMutableMap()
            val local = current.toValues()
            for (name in keepLocal) local[name]?.let { merged[name] = it }
            val result = mutablePreferencesOf()
            result.write(KeyboardSettings())
            for ((name, value) in merged) result.setIfKnown(name, value)
            prefs.clear()
            prefs.write(result.toSettings().sanitized())
        }
    }

    private fun KeyboardSettings.toValues(): MutableMap<String, Any> {
        val p = mutablePreferencesOf()
        p.write(this)
        return p.asMap().entries.associateTo(HashMap()) { it.key.name to it.value }
    }

    /** Sets [name] to [value] when it is a key of this build and the value has the right type. */
    private fun MutablePreferences.setIfKnown(name: String, value: Any) {
        when {
            name in BOOLEAN_KEYS && value is Boolean -> this[booleanPreferencesKey(name)] = value
            name in INT_KEYS && value is Int -> this[intPreferencesKey(name)] = value
            name in FLOAT_KEYS && value is Float -> this[floatPreferencesKey(name)] = value
            name in FLOAT_KEYS && value is Int -> this[floatPreferencesKey(name)] = value.toFloat()
            name in STRING_KEYS && value is String -> this[stringPreferencesKey(name)] = value
            name in STRING_SET_KEYS && value is Set<*> && value.all { it is String } ->
                this[stringSetPreferencesKey(name)] = value.filterIsInstance<String>().toSet()
        }
    }

    private fun Preferences.toSettings(): KeyboardSettings {
        val d = KeyboardSettings()
        return KeyboardSettings(
                keyHeightDp = this[KEY_HEIGHT_DP] ?: d.keyHeightDp,
                bottomKeyWidth = this[BOTTOM_KEY_WIDTH] ?: d.bottomKeyWidth,
                modeEnterKeyWidth = this[MODE_ENTER_KEY_WIDTH] ?: d.modeEnterKeyWidth,
                edgePaddingDp = this[EDGE_PADDING_DP] ?: d.edgePaddingDp,
                keyGapDp = this[KEY_GAP_DP] ?: d.keyGapDp,
                numberRow = this[NUMBER_ROW] ?: d.numberRow,
                symbolHints = this[SYMBOL_HINTS] ?: d.symbolHints,
                keyPreview = this[KEY_PREVIEW] ?: d.keyPreview,
                longPressDelayMs = this[LONG_PRESS_DELAY_MS] ?: d.longPressDelayMs,
                autoCapitalize = this[AUTO_CAPITALIZE] ?: d.autoCapitalize,
                hapticsEnabled = this[HAPTICS_ENABLED] ?: d.hapticsEnabled,
                hapticDurationMs = this[HAPTIC_DURATION_MS] ?: d.hapticDurationMs,
                hapticStrength = this[HAPTIC_STRENGTH] ?: d.hapticStrength,
                soundEnabled = this[SOUND_ENABLED] ?: d.soundEnabled,
                soundVolume = this[SOUND_VOLUME] ?: d.soundVolume,
                inlineAutofill = this[INLINE_AUTOFILL] ?: d.inlineAutofill,
                suggestionsEnabled = this[SUGGESTIONS_ENABLED] ?: d.suggestionsEnabled,
                autocorrectEnabled = this[AUTOCORRECT_ENABLED] ?: d.autocorrectEnabled,
                nextWordPredictions = this[NEXT_WORD_PREDICTIONS] ?: d.nextWordPredictions,
                phraseCompletion = this[PHRASE_COMPLETION] ?: d.phraseCompletion,
                autoSpaceAfterPick = this[AUTO_SPACE_AFTER_PICK] ?: d.autoSpaceAfterPick,
                learningEnabled = this[LEARNING_ENABLED] ?: d.learningEnabled,
                sentenceBufferEnabled = this[SENTENCE_BUFFER_ENABLED] ?: d.sentenceBufferEnabled,
                incognito = this[INCOGNITO] ?: d.incognito,
                implicitAddReverts = this[IMPLICIT_ADD_REVERTS] ?: d.implicitAddReverts,
                suppressCorrectionReverts =
                    this[SUPPRESS_CORRECTION_REVERTS] ?: d.suppressCorrectionReverts,
                nonWordTieRule = this[NON_WORD_TIE_RULE] ?: d.nonWordTieRule,
                hapticOnAutocorrect = this[HAPTIC_ON_AUTOCORRECT] ?: d.hapticOnAutocorrect,
                hapticOnUnknownWord = this[HAPTIC_ON_UNKNOWN_WORD] ?: d.hapticOnUnknownWord,
                autocorrectPulseMs = this[AUTOCORRECT_PULSE_MS] ?: d.autocorrectPulseMs,
                unknownWordPulseMs = this[UNKNOWN_WORD_PULSE_MS] ?: d.unknownWordPulseMs,
                spacebarCursor = this[SPACEBAR_CURSOR] ?: d.spacebarCursor,
                gestureTyping = this[GESTURE_TYPING] ?: d.gestureTyping,
                gestureTrail = this[GESTURE_TRAIL] ?: d.gestureTrail,
                cursorSpeed =
                    CursorSpeed.entries.firstOrNull { it.name == this[CURSOR_SPEED] }
                        ?: d.cursorSpeed,
                backspaceSwipe = this[BACKSPACE_SWIPE] ?: d.backspaceSwipe,
                emojiSuggestions = this[EMOJI_SUGGESTIONS] ?: d.emojiSuggestions,
                emojiSkinTone =
                    SkinTone.entries.firstOrNull { it.name == this[EMOJI_SKIN_TONE] }
                        ?: d.emojiSkinTone,
                clipboardHistory = this[CLIPBOARD_HISTORY] ?: d.clipboardHistory,
                clipboardMaxItems = this[CLIPBOARD_MAX_ITEMS] ?: d.clipboardMaxItems,
                clipboardRetention =
                    ClipRetention.entries.firstOrNull { it.name == this[CLIPBOARD_RETENTION] }
                        ?: d.clipboardRetention,
                clipboardImages = this[CLIPBOARD_IMAGES] ?: d.clipboardImages,
            )
            .withVoiceAndNeural(this)
    }

    /** The voice and neural fields; split from [toSettings] to keep it readable. */
    private fun KeyboardSettings.withVoiceAndNeural(p: Preferences): KeyboardSettings {
        val d = KeyboardSettings()
        return with(p) {
                copy(
                    voiceEnabled = this[VOICE_ENABLED] ?: d.voiceEnabled,
                    voiceEngine = VoiceEngineChoice.parse(this[VOICE_ENGINE]) ?: d.voiceEngine,
                    voiceAutoPunctuation = this[VOICE_AUTO_PUNCTUATION] ?: d.voiceAutoPunctuation,
                    voicePhraseMs = this[VOICE_PHRASE_MS] ?: d.voicePhraseMs,
                    voicePauseMs = this[VOICE_PAUSE_MS] ?: d.voicePauseMs,
                    voiceLiveMs = this[VOICE_LIVE_MS] ?: d.voiceLiveMs,
                    voiceAutoStopSeconds = this[VOICE_AUTO_STOP_SECONDS] ?: d.voiceAutoStopSeconds,
                    neuralRerank = this[NEURAL_RERANK] ?: d.neuralRerank,
                    neuralPhrases = this[NEURAL_PHRASES] ?: d.neuralPhrases,
                    lmStrength = this[LM_STRENGTH] ?: d.lmStrength,
                    neuralModel = this[NEURAL_MODEL] ?: d.neuralModel,
                )
            }
            .withEngine(p)
    }

    private fun KeyboardSettings.withEngine(p: Preferences): KeyboardSettings {
        val d = KeyboardSettings()
        return copy(
            neuralEngine = p[NEURAL_ENGINE] ?: d.neuralEngine,
            approvedEngines = p[APPROVED_ENGINES] ?: d.approvedEngines,
        )
    }

    private fun MutablePreferences.write(s: KeyboardSettings) {
        this[KEY_HEIGHT_DP] = s.keyHeightDp
        this[BOTTOM_KEY_WIDTH] = s.bottomKeyWidth
        this[MODE_ENTER_KEY_WIDTH] = s.modeEnterKeyWidth
        this[EDGE_PADDING_DP] = s.edgePaddingDp
        this[KEY_GAP_DP] = s.keyGapDp
        this[NUMBER_ROW] = s.numberRow
        this[SYMBOL_HINTS] = s.symbolHints
        this[KEY_PREVIEW] = s.keyPreview
        this[LONG_PRESS_DELAY_MS] = s.longPressDelayMs
        this[AUTO_CAPITALIZE] = s.autoCapitalize
        this[HAPTICS_ENABLED] = s.hapticsEnabled
        this[HAPTIC_DURATION_MS] = s.hapticDurationMs
        this[HAPTIC_STRENGTH] = s.hapticStrength
        this[SOUND_ENABLED] = s.soundEnabled
        this[SOUND_VOLUME] = s.soundVolume
        this[INLINE_AUTOFILL] = s.inlineAutofill
        this[SUGGESTIONS_ENABLED] = s.suggestionsEnabled
        this[AUTOCORRECT_ENABLED] = s.autocorrectEnabled
        this[NEXT_WORD_PREDICTIONS] = s.nextWordPredictions
        this[PHRASE_COMPLETION] = s.phraseCompletion
        this[AUTO_SPACE_AFTER_PICK] = s.autoSpaceAfterPick
        this[LEARNING_ENABLED] = s.learningEnabled
        this[SENTENCE_BUFFER_ENABLED] = s.sentenceBufferEnabled
        this[INCOGNITO] = s.incognito
        this[IMPLICIT_ADD_REVERTS] = s.implicitAddReverts
        this[SUPPRESS_CORRECTION_REVERTS] = s.suppressCorrectionReverts
        this[NON_WORD_TIE_RULE] = s.nonWordTieRule
        this[HAPTIC_ON_AUTOCORRECT] = s.hapticOnAutocorrect
        this[HAPTIC_ON_UNKNOWN_WORD] = s.hapticOnUnknownWord
        this[AUTOCORRECT_PULSE_MS] = s.autocorrectPulseMs
        this[UNKNOWN_WORD_PULSE_MS] = s.unknownWordPulseMs
        this[SPACEBAR_CURSOR] = s.spacebarCursor
        this[GESTURE_TYPING] = s.gestureTyping
        this[GESTURE_TRAIL] = s.gestureTrail
        this[CURSOR_SPEED] = s.cursorSpeed.name
        this[BACKSPACE_SWIPE] = s.backspaceSwipe
        this[EMOJI_SUGGESTIONS] = s.emojiSuggestions
        this[EMOJI_SKIN_TONE] = s.emojiSkinTone.name
        this[CLIPBOARD_HISTORY] = s.clipboardHistory
        this[CLIPBOARD_MAX_ITEMS] = s.clipboardMaxItems
        this[CLIPBOARD_RETENTION] = s.clipboardRetention.name
        this[CLIPBOARD_IMAGES] = s.clipboardImages
        this[VOICE_ENABLED] = s.voiceEnabled
        this[VOICE_ENGINE] = s.voiceEngine.name
        this[VOICE_AUTO_PUNCTUATION] = s.voiceAutoPunctuation
        this[VOICE_PHRASE_MS] = s.voicePhraseMs
        this[VOICE_PAUSE_MS] = s.voicePauseMs
        this[VOICE_LIVE_MS] = s.voiceLiveMs
        this[VOICE_AUTO_STOP_SECONDS] = s.voiceAutoStopSeconds
        this[NEURAL_RERANK] = s.neuralRerank
        this[NEURAL_PHRASES] = s.neuralPhrases
        this[LM_STRENGTH] = s.lmStrength
        this[NEURAL_MODEL] = s.neuralModel
        this[NEURAL_ENGINE] = s.neuralEngine
        this[APPROVED_ENGINES] = s.approvedEngines
    }

    private companion object {
        val KEY_HEIGHT_DP = intPreferencesKey("key_height_dp")
        val BOTTOM_KEY_WIDTH = floatPreferencesKey("bottom_key_width")
        val MODE_ENTER_KEY_WIDTH = floatPreferencesKey("mode_enter_key_width")
        val SYMBOL_HINTS = booleanPreferencesKey("symbol_hints")
        val EDGE_PADDING_DP = intPreferencesKey("edge_padding_dp")
        val KEY_GAP_DP = intPreferencesKey("key_gap_dp")
        val NUMBER_ROW = booleanPreferencesKey("number_row")
        val KEY_PREVIEW = booleanPreferencesKey("key_preview")
        val LONG_PRESS_DELAY_MS = intPreferencesKey("long_press_delay_ms")
        val AUTO_CAPITALIZE = booleanPreferencesKey("auto_capitalize")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val HAPTIC_DURATION_MS = intPreferencesKey("haptic_duration_ms")
        val HAPTIC_STRENGTH = intPreferencesKey("haptic_strength")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val SOUND_VOLUME = floatPreferencesKey("sound_volume")
        val INLINE_AUTOFILL = booleanPreferencesKey("inline_autofill")
        val SUGGESTIONS_ENABLED = booleanPreferencesKey("suggestions_enabled")
        val AUTOCORRECT_ENABLED = booleanPreferencesKey("autocorrect_enabled")
        val NEXT_WORD_PREDICTIONS = booleanPreferencesKey("next_word_predictions")
        val PHRASE_COMPLETION = booleanPreferencesKey("phrase_completion")
        val AUTO_SPACE_AFTER_PICK = booleanPreferencesKey("auto_space_after_pick")
        val LEARNING_ENABLED = booleanPreferencesKey("learning_enabled")
        val SENTENCE_BUFFER_ENABLED = booleanPreferencesKey("sentence_buffer_enabled")
        val INCOGNITO = booleanPreferencesKey("incognito")
        val IMPLICIT_ADD_REVERTS = intPreferencesKey("implicit_add_reverts")
        val SUPPRESS_CORRECTION_REVERTS = intPreferencesKey("suppress_correction_reverts")
        val NON_WORD_TIE_RULE = booleanPreferencesKey("non_word_tie_rule")
        val HAPTIC_ON_AUTOCORRECT = booleanPreferencesKey("haptic_on_autocorrect")
        val HAPTIC_ON_UNKNOWN_WORD = booleanPreferencesKey("haptic_on_unknown_word")
        val AUTOCORRECT_PULSE_MS = intPreferencesKey("autocorrect_pulse_ms")
        val UNKNOWN_WORD_PULSE_MS = intPreferencesKey("unknown_word_pulse_ms")
        val SPACEBAR_CURSOR = booleanPreferencesKey("spacebar_cursor")
        val GESTURE_TYPING = booleanPreferencesKey("gesture_typing")
        val GESTURE_TRAIL = booleanPreferencesKey("gesture_trail")
        val CURSOR_SPEED = stringPreferencesKey("cursor_speed")
        val BACKSPACE_SWIPE = booleanPreferencesKey("backspace_swipe")
        val EMOJI_SUGGESTIONS = booleanPreferencesKey("emoji_suggestions")
        val EMOJI_SKIN_TONE = stringPreferencesKey("emoji_skin_tone")
        val CLIPBOARD_HISTORY = booleanPreferencesKey("clipboard_history")
        val CLIPBOARD_MAX_ITEMS = intPreferencesKey("clipboard_max_items")
        val CLIPBOARD_RETENTION = stringPreferencesKey("clipboard_retention")
        val CLIPBOARD_IMAGES = booleanPreferencesKey("clipboard_images")
        val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
        val VOICE_ENGINE = stringPreferencesKey("voice_engine")
        val VOICE_AUTO_PUNCTUATION = booleanPreferencesKey("voice_auto_punctuation")
        val VOICE_PHRASE_MS = intPreferencesKey("voice_phrase_ms")
        val VOICE_PAUSE_MS = intPreferencesKey("voice_pause_ms")
        val VOICE_LIVE_MS = intPreferencesKey("voice_live_ms")
        val VOICE_AUTO_STOP_SECONDS = intPreferencesKey("voice_auto_stop_seconds")
        val NEURAL_RERANK = booleanPreferencesKey("neural_rerank")
        val NEURAL_PHRASES = booleanPreferencesKey("neural_phrases")
        val LM_STRENGTH = floatPreferencesKey("lm_strength")
        val NEURAL_MODEL = stringPreferencesKey("neural_model")
        val NEURAL_ENGINE = stringPreferencesKey("neural_engine")
        val APPROVED_ENGINES = stringSetPreferencesKey("approved_engines")
        val BOOLEAN_KEYS =
            setOf(
                SYMBOL_HINTS.name,
                NUMBER_ROW.name,
                KEY_PREVIEW.name,
                AUTO_CAPITALIZE.name,
                HAPTICS_ENABLED.name,
                SOUND_ENABLED.name,
                INLINE_AUTOFILL.name,
                SUGGESTIONS_ENABLED.name,
                AUTOCORRECT_ENABLED.name,
                NEXT_WORD_PREDICTIONS.name,
                PHRASE_COMPLETION.name,
                AUTO_SPACE_AFTER_PICK.name,
                LEARNING_ENABLED.name,
                SENTENCE_BUFFER_ENABLED.name,
                INCOGNITO.name,
                NON_WORD_TIE_RULE.name,
                HAPTIC_ON_AUTOCORRECT.name,
                HAPTIC_ON_UNKNOWN_WORD.name,
                SPACEBAR_CURSOR.name,
                GESTURE_TYPING.name,
                GESTURE_TRAIL.name,
                BACKSPACE_SWIPE.name,
                EMOJI_SUGGESTIONS.name,
                CLIPBOARD_HISTORY.name,
                CLIPBOARD_IMAGES.name,
                VOICE_ENABLED.name,
                VOICE_AUTO_PUNCTUATION.name,
                NEURAL_RERANK.name,
                NEURAL_PHRASES.name,
            )
        val INT_KEYS =
            setOf(
                KEY_HEIGHT_DP.name,
                EDGE_PADDING_DP.name,
                KEY_GAP_DP.name,
                LONG_PRESS_DELAY_MS.name,
                HAPTIC_DURATION_MS.name,
                HAPTIC_STRENGTH.name,
                IMPLICIT_ADD_REVERTS.name,
                SUPPRESS_CORRECTION_REVERTS.name,
                AUTOCORRECT_PULSE_MS.name,
                UNKNOWN_WORD_PULSE_MS.name,
                CLIPBOARD_MAX_ITEMS.name,
                VOICE_PHRASE_MS.name,
                VOICE_PAUSE_MS.name,
                VOICE_LIVE_MS.name,
                VOICE_AUTO_STOP_SECONDS.name,
            )
        val FLOAT_KEYS =
            setOf(
                BOTTOM_KEY_WIDTH.name,
                MODE_ENTER_KEY_WIDTH.name,
                SOUND_VOLUME.name,
                LM_STRENGTH.name,
            )
        val STRING_KEYS =
            setOf(
                CURSOR_SPEED.name,
                EMOJI_SKIN_TONE.name,
                CLIPBOARD_RETENTION.name,
                VOICE_ENGINE.name,
                NEURAL_MODEL.name,
                NEURAL_ENGINE.name,
            )
        val STRING_SET_KEYS = setOf(APPROVED_ENGINES.name)
    }
}

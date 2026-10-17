package dev.keycorrect.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
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

    private fun Preferences.toSettings(): KeyboardSettings {
        val d = KeyboardSettings()
        return KeyboardSettings(
            keyHeightDp = this[KEY_HEIGHT_DP] ?: d.keyHeightDp,
            bottomKeyWidth = this[BOTTOM_KEY_WIDTH] ?: d.bottomKeyWidth,
            edgePaddingDp = this[EDGE_PADDING_DP] ?: d.edgePaddingDp,
            keyGapDp = this[KEY_GAP_DP] ?: d.keyGapDp,
            numberRow = this[NUMBER_ROW] ?: d.numberRow,
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
            spacebarCursor = this[SPACEBAR_CURSOR] ?: d.spacebarCursor,
            cursorSpeed =
                CursorSpeed.entries.firstOrNull { it.name == this[CURSOR_SPEED] } ?: d.cursorSpeed,
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
    }

    private fun MutablePreferences.write(s: KeyboardSettings) {
        this[KEY_HEIGHT_DP] = s.keyHeightDp
        this[BOTTOM_KEY_WIDTH] = s.bottomKeyWidth
        this[EDGE_PADDING_DP] = s.edgePaddingDp
        this[KEY_GAP_DP] = s.keyGapDp
        this[NUMBER_ROW] = s.numberRow
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
        this[SPACEBAR_CURSOR] = s.spacebarCursor
        this[CURSOR_SPEED] = s.cursorSpeed.name
        this[BACKSPACE_SWIPE] = s.backspaceSwipe
        this[EMOJI_SUGGESTIONS] = s.emojiSuggestions
        this[EMOJI_SKIN_TONE] = s.emojiSkinTone.name
        this[CLIPBOARD_HISTORY] = s.clipboardHistory
        this[CLIPBOARD_MAX_ITEMS] = s.clipboardMaxItems
        this[CLIPBOARD_RETENTION] = s.clipboardRetention.name
        this[CLIPBOARD_IMAGES] = s.clipboardImages
    }

    private companion object {
        val KEY_HEIGHT_DP = intPreferencesKey("key_height_dp")
        val BOTTOM_KEY_WIDTH = floatPreferencesKey("bottom_key_width")
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
        val SPACEBAR_CURSOR = booleanPreferencesKey("spacebar_cursor")
        val CURSOR_SPEED = stringPreferencesKey("cursor_speed")
        val BACKSPACE_SWIPE = booleanPreferencesKey("backspace_swipe")
        val EMOJI_SUGGESTIONS = booleanPreferencesKey("emoji_suggestions")
        val EMOJI_SKIN_TONE = stringPreferencesKey("emoji_skin_tone")
        val CLIPBOARD_HISTORY = booleanPreferencesKey("clipboard_history")
        val CLIPBOARD_MAX_ITEMS = intPreferencesKey("clipboard_max_items")
        val CLIPBOARD_RETENTION = stringPreferencesKey("clipboard_retention")
        val CLIPBOARD_IMAGES = booleanPreferencesKey("clipboard_images")
    }
}

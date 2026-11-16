package dev.keycorrect.feature.keyboard

import dev.keycorrect.core.emoji.Emoji
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.KeyboardSet
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.emoji.EmojiChoice
import dev.keycorrect.feature.keyboard.emoji.EmojiEnvironment
import dev.keycorrect.feature.keyboard.emoji.EmojiSearch
import dev.keycorrect.feature.keyboard.prediction.PredictiveInput

/**
 * The emoji panel's actions: inserting a picked emoji or kaomoji, remembering variant choices, and
 * opening and leaving the emoji search. The keys typed into the search stay with the keyboard's
 * shift and layer state (see [KeyboardController]).
 */
@Suppress("LongParameterList") // the controller's state reaches it as lambdas
internal class EmojiPanelController(
    private val emoji: EmojiEnvironment,
    private val emojiSearch: EmojiSearch,
    private val input: PredictiveInput,
    private val tracked: TrackedEditor,
    private val profile: () -> FieldProfile,
    private val settings: () -> KeyboardSettings,
    private val panel: () -> KeyboardPanel,
    private val layerId: () -> String,
    private val switchLayer: (String) -> Unit,
    private val updateAutoCapitalize: () -> Unit,
) {
    /**
     * An emoji or kaomoji [value] was picked in the emoji panel ([base] is its grid entry; it is
     * [record]ed as used unless a kaomoji). Nothing happens in a sensitive field.
     *
     * Settling: like a cursor move ([PredictiveInput.endComposingForMove]), not like a separator.
     * The composing word is finished as typed and abandoned rather than learned or autocorrected,
     * pending suggestions and a held revert are dropped, and the text cache is reset (it is read
     * again on the next word). The emoji is then committed through [tracked], so [OwnEditTracker]
     * expects its selection update. An emoji is never a word and never a separator.
     */
    fun onEmojiPicked(value: String, base: String = value, record: Boolean = true) {
        if (profile().sensitive || value.isEmpty()) return
        input.endComposingForMove()
        tracked.commitText(value, 1)
        // Nothing is remembered where the field asks for no learning (incognito tabs and the like).
        if (record && profile().learningEnabled) emoji.onEmojiUsed(value, base)
        updateAutoCapitalize()
    }

    /** A grid emoji was tapped: inserts its preferred variant, else the default tone, else base. */
    fun onEmojiTap(item: Emoji) = onEmojiPicked(emojiValue(item), item.value)

    /** The form of [item] a tap inserts. */
    fun emojiValue(item: Emoji): String =
        EmojiChoice.resolve(item, emoji.preferredVariant(item.value), settings().emojiSkinTone)

    /** [variant] was chosen from [item]'s long-press popup: inserts it and remembers the choice. */
    fun onEmojiVariantPicked(item: Emoji, variant: String) {
        if (profile().sensitive) return
        if (profile().learningEnabled) emoji.setPreferredVariant(item.value, variant)
        onEmojiPicked(variant, item.value)
    }

    /** Switches the emoji panel to search: the letters layer, typing edits the query. */
    fun openEmojiSearch() {
        if (profile().sensitive || panel() != KeyboardPanel.Emoji) return
        emojiSearch.open()
        if (layerId() != KeyboardSet.LETTERS) switchLayer(KeyboardSet.LETTERS)
    }

    /** Leaves the emoji search, back to the emoji grid. */
    fun closeEmojiSearch() {
        emojiSearch.close()
        updateAutoCapitalize()
    }
}

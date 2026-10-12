package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.emoji.Emoji
import dev.keycorrect.core.emoji.EmojiCatalog
import dev.keycorrect.core.emoji.EmojiGroup
import dev.keycorrect.core.emoji.EmojiVariant
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.SkinTone
import dev.keycorrect.feature.keyboard.emoji.FakeEmojiEnvironment
import dev.keycorrect.feature.keyboard.prediction.SlotKind
import org.junit.Test

/** The strip's emoji comes in the form the panel would insert: the chosen variant or skin tone. */
class EmojiSuggestionFormTest {
    private val wave =
        Emoji(
            "👋",
            "waving hand",
            listOf("wave"),
            listOf(
                EmojiVariant("👋🏽", "medium skin tone"),
                EmojiVariant("👋🏿", "dark skin tone"),
            ),
            "People",
        )
    private val environment =
        FakeEmojiEnvironment(
            EmojiCatalog("17.0", "48.2", listOf(EmojiGroup("People", listOf(wave))), emptyList())
        )
    private val editor = SimEditor()
    private val backend = FakeBackend().apply { predictor = ScriptedPredictor() }
    private val controller =
        KeyboardController(
            editor,
            KeyboardComposer(BuiltInLayouts.repository()),
            testEnv(backend),
            emoji = environment,
        )

    private fun typeWave(settings: KeyboardSettings) {
        controller.onStartInput(
            FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0),
            settings,
            identity = EditorIdentity(1, "pkg", 1, 0, null),
        )
        "wave".forEach { controller.onKey(KeyAction.Text("$it")) }
    }

    @Test
    fun theDefaultSkinToneIsShownAndInserted() {
        typeWave(KeyboardSettings(emojiSkinTone = SkinTone.Medium))
        val slot = controller.strip.right!!
        assertThat(slot.kind).isEqualTo(SlotKind.Emoji)
        assertThat(slot.text).isEqualTo("👋🏽")
        controller.onSuggestionTap(slot)
        assertThat(editor.text.toString()).startsWith("👋🏽")
        assertThat(environment.used).containsExactly("👋🏽" to "👋")
    }

    @Test
    fun aChosenVariantWinsOverTheDefaultTone() {
        environment.preferred["👋"] = "👋🏿"
        typeWave(KeyboardSettings(emojiSkinTone = SkinTone.Medium))
        assertThat(controller.strip.right?.text).isEqualTo("👋🏿")
    }
}

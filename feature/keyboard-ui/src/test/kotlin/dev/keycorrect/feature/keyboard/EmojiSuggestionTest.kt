package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.emoji.Emoji
import dev.keycorrect.core.emoji.EmojiCatalog
import dev.keycorrect.core.emoji.EmojiGroup
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.emoji.EmojiEnvironment
import dev.keycorrect.feature.keyboard.prediction.SlotKind
import org.junit.Test

/** The emoji chip in the strip (docs/PHASE4.md P4-5): a strong match, right slot, not learned. */
class EmojiSuggestionTest {
    private val pizza = Emoji("🍕", "pizza", listOf("cheese", "slice"), emptyList(), "Food")
    private val grin = Emoji("😀", "grinning face", listOf("happy"), emptyList(), "Smileys")
    private val heart = Emoji("❤️", "red heart", listOf("happy"), emptyList(), "Smileys")
    private val catalog =
        EmojiCatalog(
            "17.0",
            "48.2",
            listOf(EmojiGroup("All", listOf(pizza, grin, heart))),
            emptyList(),
        )

    private val used = mutableListOf<Pair<String, String>>()
    private val environment =
        object : EmojiEnvironment {
            override val catalog: EmojiCatalog = this@EmojiSuggestionTest.catalog
            override val recents: List<String> = emptyList()

            override fun preferredVariant(base: String): String? = null

            override fun setPreferredVariant(base: String, variant: String?) = Unit

            override fun onEmojiUsed(value: String, base: String) {
                used += value to base
            }
        }

    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val predictor = ScriptedPredictor()
    private val sink = RecordingSink()
    private val controller: KeyboardController

    init {
        backend.predictor = predictor
        backend.known += listOf("pizza", "pizzas", "happy", "the")
        controller =
            KeyboardController(
                editor,
                KeyboardComposer(BuiltInLayouts.repository()),
                testEnv(backend),
                learning = sink,
                emoji = environment,
            )
    }

    private val textField = FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0)

    private fun start(
        profile: FieldProfile = textField,
        settings: KeyboardSettings = KeyboardSettings(),
    ) {
        controller.onStartInput(
            profile,
            settings,
            restarting = false,
            identity = EditorIdentity(1, "pkg", 1, 0, null),
            initialSelStart = editor.cursor,
            initialSelEnd = editor.cursor,
        )
    }

    private fun type(text: String) = text.forEach { controller.onKey(KeyAction.Text("$it")) }

    private fun text() = editor.text.toString()

    @Test
    fun aStrongMatchTakesTheRightSlot() {
        predictor.word("pizza", listOf("pizzas", "pizzeria", "pizzazz", "pizzicato"))
        start()
        type("pizza")
        val right = controller.strip.right!!
        assertThat(right.kind).isEqualTo(SlotKind.Emoji)
        assertThat(right.text).isEqualTo(pizza.value)
        assertThat(right.label).isEqualTo("pizza")
        assertThat(controller.strip.centre?.kind).isEqualTo(SlotKind.Suggestion)
        assertThat(controller.strip.centre?.text).isEqualTo("pizzas")
    }

    @Test
    fun theMatchIsLookedUpAsTyped() {
        start()
        type("Pizza")
        assertThat(controller.strip.right?.kind).isEqualTo(SlotKind.Emoji)
    }

    @Test
    fun theAutocorrectInTheCentreIsUnaffected() {
        predictor.word("pizz", listOf("pizza"), autocorrect = "pizza")
        predictor.word("pizza", listOf("pizzas"), autocorrect = "pizzas")
        start()
        type("pizza")
        assertThat(controller.strip.centre?.kind).isEqualTo(SlotKind.Autocorrect)
        assertThat(controller.strip.centre?.text).isEqualTo("pizzas")
        assertThat(controller.strip.right?.kind).isEqualTo(SlotKind.Emoji)
        controller.onKey(KeyAction.Space)
        assertThat(text()).isEqualTo("pizzas ")
        assertThat(used).isEmpty()
    }

    @Test
    fun noEmojiWhenTheSettingIsOff() {
        start(settings = KeyboardSettings(emojiSuggestions = false))
        type("pizza")
        assertThat(controller.strip.right?.kind).isNotEqualTo(SlotKind.Emoji)
    }

    @Test
    fun noEmojiInASensitiveField() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                0,
            )
        )
        type("pizza")
        assertThat(controller.strip.isEmpty).isTrue()
    }

    @Test
    fun weakWordsGetNoEmoji() {
        start()
        for (word in listOf("the", "happy", "pi", "zzzz")) {
            type(word)
            assertThat(controller.strip.right?.kind).isNotEqualTo(SlotKind.Emoji)
            controller.onKey(KeyAction.Space)
        }
    }

    @Test
    fun noEmojiBeforeAWordIsStarted() {
        predictor.nextWords = listOf("pizza", "the", "a")
        start()
        type("go ")
        val kinds = listOf(controller.strip.left, controller.strip.centre, controller.strip.right)
        assertThat(kinds.map { it?.kind }).doesNotContain(SlotKind.Emoji)
    }

    @Test
    fun tappingReplacesTheWordAndLearningHearsNothing() {
        start()
        type("pizza")
        controller.onSuggestionTap(controller.strip.right!!)
        assertThat(text()).isEqualTo("${pizza.value} ")
        assertThat(used).containsExactly(pizza.value to pizza.value)
        assertThat(sink.isSilent).isTrue()
        assertThat(controller.strip.right?.kind).isNotEqualTo(SlotKind.Emoji)
        // Typing goes on as a new word.
        type("ok")
        assertThat(text()).isEqualTo("${pizza.value} ok")
    }

    @Test
    fun noSpaceWhenAutoSpaceAfterPickIsOff() {
        start(settings = KeyboardSettings(autoSpaceAfterPick = false))
        type("pizza")
        controller.onSuggestionTap(controller.strip.right!!)
        assertThat(text()).isEqualTo(pizza.value)
    }

    @Test
    fun backspaceDeletesTheEmojiLikeACharacter() {
        start(settings = KeyboardSettings(autoSpaceAfterPick = false))
        type("pizza")
        controller.onSuggestionTap(controller.strip.right!!)
        controller.onKey(KeyAction.Backspace)
        assertThat(text()).isEmpty()
        assertThat(sink.isSilent).isTrue()
    }
}

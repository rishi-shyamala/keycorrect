package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.dictionary.BigramEntry
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.dictionary.DictionaryData
import dev.keycorrect.core.dictionary.DictionaryMetadata
import dev.keycorrect.core.dictionary.DictionaryWriter
import dev.keycorrect.core.dictionary.WordEntry
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.prediction.Predictor
import java.nio.ByteBuffer
import org.junit.Test

/** The controller wired to the real [ClassicEngine] over a tiny synthetic dictionary. */
class RealEngineControllerTest {
    private val words =
        mapOf(
            "the" to 260_000L,
            "to" to 130_000L,
            "of" to 120_000L,
            "and" to 110_000L,
            "hello" to 20_000L,
            "world" to 15_000L,
            "help" to 12_000L,
            "there" to 40_000L,
            "zebra" to 2L,
        )

    private val dictionary: Dictionary by lazy {
        val total = 2_000_000L
        val data =
            DictionaryData(
                DictionaryMetadata("en-US", "2026-10-09", 1, "test data"),
                words.map { (w, c) -> WordEntry.fromCount(w, c, total, 0) },
                listOf(
                    BigramEntry.fromCount("hello", "world", 50, 50),
                    BigramEntry.fromCount(Dictionary.BOS_TOKEN, "hello", 50, 50),
                ),
                emptyList(),
            )
        Dictionary.open(ByteBuffer.wrap(DictionaryWriter.write(data, skipUnknownNgrams = true)))
    }

    private val editor = SimEditor()
    private val backend = FakeBackend()
    private var now = 1_000L
    private val controller =
        KeyboardController(
            editor,
            KeyboardComposer(BuiltInLayouts.repository()),
            testEnv(backend),
            clock = { now },
        )

    private fun start() {
        val engine = ClassicEngine(dictionary)
        backend.predictor = Predictor { request, stale -> engine.predictNow(request, stale) }
        controller.onStartInput(
            FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0),
            KeyboardSettings(),
        )
        controller.onGeometry(KeyboardGeometry(controller.layer, 1080f, 520f))
    }

    private fun type(text: String) = text.forEach { controller.onKey(KeyAction.Text("$it")) }

    @Test
    fun completionsComeFromTheDictionary() {
        start()
        type("hel")
        val shown =
            listOfNotNull(controller.strip.left, controller.strip.centre, controller.strip.right)
        assertThat(shown.map { it.text }).containsAtLeast("hello", "help")
    }

    @Test
    fun aTypoIsCorrectedOnSpaceAndTheCorrectionCanBeUndone() {
        start()
        type("teh")
        controller.onKey(KeyAction.Space)
        assertThat(editor.text.toString()).isEqualTo("the ")
        controller.onKey(KeyAction.Backspace)
        assertThat(editor.text.toString()).isEqualTo("teh ")
    }

    @Test
    fun aKnownWordIsLeftAlone() {
        start()
        type("hello")
        controller.onKey(KeyAction.Space)
        assertThat(editor.text.toString()).isEqualTo("hello ")
        assertThat(controller.strip.centre?.text).isEqualTo("world")
    }
}

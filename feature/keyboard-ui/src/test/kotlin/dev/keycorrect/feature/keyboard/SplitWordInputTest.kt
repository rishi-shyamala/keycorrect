package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.decoder.ClassicConfig
import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.decoder.SplitConfig
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
import dev.keycorrect.core.learning.AutocorrectReverted
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.RevertKind
import dev.keycorrect.core.learning.WordCommitted
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.prediction.Predictor
import dev.keycorrect.feature.keyboard.prediction.StripSlot
import java.nio.ByteBuffer
import org.junit.Test

/**
 * A missing space ("ofthe" -> "of the") through the controller and the real engine: picking the
 * split, its capitalisation, undoing an applied one, re-correcting one. The split is recognised by
 * the candidate's flag carried on the strip slot, and the joined text is never reported as a word.
 */
class SplitWordInputTest {
    private val words =
        mapOf(
            "the" to 260_000L,
            "of" to 120_000L,
            "and" to 110_000L,
            "hello" to 20_000L,
            "say" to 9_000L,
        )

    private val dictionary: Dictionary by lazy {
        val total = 2_000_000L
        val data =
            DictionaryData(
                DictionaryMetadata("en-US", "2026-10-09", 1, "test data"),
                words.map { (w, c) -> WordEntry.fromCount(w, c, total, 0) },
                listOf(BigramEntry.fromCount(Dictionary.BOS_TOKEN, "hello", 50, 50)),
                emptyList(),
            )
        Dictionary.open(ByteBuffer.wrap(DictionaryWriter.write(data, skipUnknownNgrams = true)))
    }

    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val sink = RecordingSink()
    private var now = 1_000L
    private val controller =
        KeyboardController(
            editor,
            KeyboardComposer(BuiltInLayouts.repository()),
            testEnv(backend),
            clock = { now },
            learning = sink,
        )

    private fun start(
        autocorrect: Boolean = false,
        settings: KeyboardSettings = KeyboardSettings(),
    ) {
        val config =
            ClassicConfig(split = SplitConfig(autocorrect = autocorrect, autocorrectMargin = -100f))
        val engine = ClassicEngine(dictionary, config)
        backend.predictor = Predictor { request, stale -> engine.predictNow(request, stale) }
        controller.onStartInput(FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0), settings)
        controller.onGeometry(KeyboardGeometry(controller.layer, 1080f, 520f))
    }

    private fun type(text: String) = text.forEach {
        if (it == ' ') controller.onKey(KeyAction.Space)
        else controller.onKey(KeyAction.Text("$it"))
    }

    private fun text() = editor.text.toString()

    private fun slots() =
        listOfNotNull(controller.strip.left, controller.strip.centre, controller.strip.right)

    private fun splitSlot(text: String): StripSlot =
        slots().single { it.isSplit && it.insert == text }

    private fun commits() = sink.of<WordCommitted>()

    private fun assertJoinedNeverReported() {
        assertThat(commits().map { it.committed }).doesNotContain("ofthe")
        assertThat(commits().filter { !it.isSplit }.map { it.committed }).doesNotContain("of the")
    }

    @Test
    fun aPickedSplitIsTwoWordsWithOneSpaceAndTheCursorAfterIt() {
        start()
        type("hello ofthe")
        val slot = splitSlot("of the")
        controller.onSuggestionTap(slot)
        assertThat(text()).isEqualTo("hello of the ")
        assertThat(editor.cursor).isEqualTo(text().length)
        assertThat(editor.composing).isEmpty()
        val event = commits().single { it.committed == "of the" }
        assertThat(event.typed).isEqualTo("ofthe")
        assertThat(event.method).isEqualTo(CommitMethod.SuggestionPicked)
        assertThat(event.isSplit).isTrue()
        assertJoinedNeverReported()
    }

    @Test
    fun aPickedSplitWithoutAutoSpaceEndsAtTheLastLetter() {
        start(settings = KeyboardSettings(autoSpaceAfterPick = false))
        type("ofthe")
        controller.onSuggestionTap(splitSlot("of the"))
        assertThat(text()).isEqualTo("of the")
        assertThat(editor.cursor).isEqualTo(6)
    }

    @Test
    fun theFirstWordOfASentenceKeepsItsCapitalInTheSplit() {
        start()
        type("Ofthe")
        controller.onSuggestionTap(splitSlot("Of the"))
        assertThat(text()).isEqualTo("Of the ")
        assertThat(commits().single { it.isSplit }.committed).isEqualTo("Of the")
    }

    @Test
    fun anAppliedSplitIsUndoneByOneBackspaceAndReportedAsAReject() {
        start(autocorrect = true)
        type("ofthe")
        assertThat(controller.strip.centre?.isSplit).isTrue()
        type(" ")
        assertThat(text()).isEqualTo("of the ")
        controller.onKey(KeyAction.Backspace)
        assertThat(text()).isEqualTo("ofthe")
        // Finishing it again keeps the literal (no second correction), and moving on settles it.
        type(" and ")
        assertThat(text()).isEqualTo("ofthe and ")
        val revert = sink.of<AutocorrectReverted>().single()
        assertThat(revert.typed).isEqualTo("ofthe")
        assertThat(revert.corrected).isEqualTo("of the")
        assertThat(revert.kind).isEqualTo(RevertKind.ImmediateBackspace)
        assertJoinedNeverReported()
    }

    @Test
    fun anAppliedSplitThatStandsIsConfirmedAsACorrectionNotAWord() {
        start(autocorrect = true)
        type("ofthe and ")
        assertThat(text()).isEqualTo("of the and ")
        type("hello ")
        val kept = commits().single { it.isSplit }
        assertThat(kept.method).isEqualTo(CommitMethod.Autocorrected)
        assertThat(kept.typed).isEqualTo("ofthe")
        assertThat(kept.committed).isEqualTo("of the")
        assertJoinedNeverReported()
    }

    @Test
    fun aSplitWordIsRecorrectedInPlace() {
        start()
        editor.setText("say ofthe", 9)
        editor.echo(controller)
        val slot = splitSlot("of the")
        controller.onSuggestionTap(slot)
        assertThat(text()).isEqualTo("say of the")
        assertThat(editor.cursor).isEqualTo(10)
        val event = commits().single { it.isSplit }
        assertThat(event.typed).isEqualTo("ofthe")
        assertThat(event.committed).isEqualTo("of the")
        assertJoinedNeverReported()
    }
}

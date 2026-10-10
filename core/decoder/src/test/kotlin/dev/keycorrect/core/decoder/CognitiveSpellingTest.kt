package dev.keycorrect.core.decoder

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.dictionary.DictionaryData
import dev.keycorrect.core.dictionary.DictionaryMetadata
import dev.keycorrect.core.dictionary.DictionaryWriter
import dev.keycorrect.core.dictionary.WordEntry
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.TypingContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Cognitive misspellings (ie/ei, vowel-for-vowel, doubled letters) typed with key-centre taps:
 * rules on a small synthetic dictionary whose unigram log-probabilities mimic the real ones (a very
 * frequent competitor beside a rarer intended word), and the real dictionary asset when present
 * (docs/EVAL.md, "Cognitive misspellings").
 */
class CognitiveSpellingTest {
    private val synthetic: Dictionary by lazy {
        val words =
            mapOf(
                "the" to -2.25f,
                "this" to -3.85f,
                "they" to -3.75f,
                "their" to -6.66f,
                "there" to -4.6f,
                "where" to -5.82f,
                "were" to -7.6f,
                "weird" to -11.46f,
                "wired" to -13.1f,
                "separate" to -10.85f,
                "desperate" to -11.9f,
                "receive" to -11.34f,
                "received" to -9.56f,
                "believe" to -9.0f,
                "friend" to -8.0f,
                "friends" to -8.5f,
                "until" to -8.0f,
                "occurred" to -11.0f,
                "address" to -9.5f,
                "recommend" to -11.0f,
                "definite" to -9.5f,
                "rare" to -16f,
            )
        val data =
            DictionaryData(
                DictionaryMetadata("en-US", "2026-10-10", 1, "cognitive test data"),
                words.map { (w, lp) -> WordEntry(w, lp) },
            )
        Dictionary.open(ByteBuffer.wrap(DictionaryWriter.write(data, skipUnknownNgrams = true)))
    }

    private fun applied(
        dictionary: Dictionary,
        typed: String,
        config: ClassicConfig = ClassicConfig(),
        before: String = "",
    ): String? {
        val engine = ClassicEngine(dictionary, config)
        val r =
            engine.predictNow(
                PredictRequest(
                    TypingContext(before),
                    TestKeyboard.typed(typed),
                    TestKeyboard.layout,
                    3,
                    autocorrectAllowed = true,
                )
            )
        return r.autocorrect?.text
    }

    private fun applied(typed: String, config: ClassicConfig = ClassicConfig()) =
        applied(synthetic, typed, config)

    // ------------------------------------------------------------------ rules (synthetic)

    @Test
    fun ieEiSwapBeatsFrequentTwoEditWords() {
        assertThat(applied("thier")).isEqualTo("their")
        assertThat(applied("wierd")).isEqualTo("weird")
        assertThat(applied("recieve")).isEqualTo("receive")
        assertThat(applied("freind")).isEqualTo("friend")
        assertThat(applied("beleive")).isEqualTo("believe")
    }

    @Test
    fun vowelForVowelIsCorrectedWhateverTheKeyDistance() {
        assertThat(applied("seperate")).isEqualTo("separate")
        assertThat(applied("definate")).isEqualTo("definite")
    }

    @Test
    fun missingOrExtraDoubledLetterIsCheap() {
        assertThat(applied("occured")).isEqualTo("occurred")
        assertThat(applied("adress")).isEqualTo("address")
        assertThat(applied("recomend")).isEqualTo("recommend")
        assertThat(applied("untill")).isEqualTo("until")
    }

    @Test
    fun legacyCostsDoNotFindTheVowelSubstitution() {
        val legacy =
            ClassicConfig(
                edits =
                    EditConfig(
                        vowelTranspositionPenalty = -3f,
                        phoneticSubstitutions = false,
                        multiEditPenalty = 0f,
                    )
            )
        assertThat(applied("thier", legacy)).isNotEqualTo("their")
    }

    @Test
    fun anExtraEditCostsMoreThanTheFirst() {
        val cheap = ClassicConfig(edits = EditConfig(multiEditPenalty = 0f))
        val dear = ClassicConfig(edits = EditConfig(multiEditPenalty = -4f))
        fun editsOf(c: ClassicConfig): Float {
            val r =
                ClassicEngine(synthetic, c)
                    .predictRanked(
                        PredictRequest(
                            TypingContext(""),
                            TestKeyboard.typed("thier"),
                            TestKeyboard.layout,
                            8,
                        )
                    )
            return r.candidates.first { it.text == "the" }.breakdown.edits
        }
        assertThat(editsOf(dear)).isLessThan(editsOf(cheap))
    }

    // ------------------------------------------------------------------ real dictionary

    private val realFile: File? by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/assets/dictionaries/en_US.kcd")
            if (f.isFile) return@lazy f
            dir = dir.parentFile
        }
        null
    }

    private fun real(): Dictionary {
        val f = realFile
        assumeTrue("en_US.kcd asset not present", f != null)
        return FileChannel.open(f!!.toPath(), StandardOpenOption.READ).use {
            Dictionary.open(it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()))
        }
    }

    @Test
    fun realDictionaryCommonMisspellings() {
        val d = real()
        val wanted =
            mapOf(
                "thier" to "their",
                "wierd" to "weird",
                "seperate" to "separate",
                "recieve" to "receive",
                "beleive" to "believe",
                "freind" to "friend",
                "teh" to "the",
            )
        val got = wanted.keys.associateWith { applied(d, it) }
        assertThat(got).isEqualTo(wanted)
    }
}

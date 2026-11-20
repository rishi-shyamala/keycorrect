package dev.keycorrect.tools.eval

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.Test

class RealSwipesTest {
    private val keyboard = EvalKeyboard()

    @Test
    fun punctuationAroundAWordIsTrimmedButNotInside() {
        assertThat(RealSwipes.trimPunctuation("Atri,")).isEqualTo("Atri")
        assertThat(RealSwipes.trimPunctuation("(the")).isEqualTo("the")
        assertThat(RealSwipes.trimPunctuation("don't.")).isEqualTo("don't")
    }

    @Test
    fun onlyMultiLetterWordsOnTheLayerAreSwipeable() {
        assertThat(RealSwipes.swipeable("don't", keyboard)).isTrue()
        assertThat(RealSwipes.swipeable("a", keyboard)).isFalse()
        assertThat(RealSwipes.swipeable("4th", keyboard)).isFalse()
        assertThat(RealSwipes.swipeable("café", keyboard)).isFalse()
    }

    @Test
    fun contextIsTheSentenceBeforeTheWord() {
        assertThat(RealSwipes.context("The cat sat", 2)).isEqualTo("The cat ")
        assertThat(RealSwipes.context("The cat sat", 0)).isEmpty()
        assertThat(RealSwipes.context("The cat sat", 9)).isEmpty()
    }

    @Test
    fun affineFitRecoversAnExactMap() {
        val src =
            listOf(
                doubleArrayOf(0.0, 0.0),
                doubleArrayOf(1.0, 0.0),
                doubleArrayOf(0.0, 1.0),
                doubleArrayOf(0.5, 0.5),
            )
        val dst = src.map {
            doubleArrayOf(100 * it[0] + 3 * it[1] + 7, -2 * it[0] + 50 * it[1] + 11)
        }
        val map = AffineMap.fit(src, dst, keyWidth = 10.0)
        assertThat(map.rmsKeyWidths).isLessThan(1e-9)
        assertThat(map.mapX(0.25, 0.75).toDouble()).isWithin(1e-4).of(100 * 0.25 + 3 * 0.75 + 7)
        assertThat(map.mapY(0.25, 0.75).toDouble()).isWithin(1e-4).of(-2 * 0.25 + 50 * 0.75 + 11)
    }

    @Test
    fun loadMapsPointsOntoTheKeyboardAndCountsWhatItSkips() {
        val dir = Files.createTempDirectory("realswipes")
        val layout = dir.resolve("qwerty.json")
        // Three letter centres in the dataset's normalised frame (row centres at 1/6 and 1/2).
        Files.writeString(
            layout,
            """{"keys": [
              {"letter": "q", "cx": 0.05, "cy": 0.1666666},
              {"letter": "p", "cx": 0.95, "cy": 0.1666666},
              {"letter": "a", "cx": 0.1, "cy": 0.5}
            ]}""",
        )
        val rows = dir.resolve("rows.jsonl")
        Files.writeString(
            rows,
            listOf(
                    row(
                        "Atri,",
                        "Brahmas son was Atri,",
                        3,
                        "[" + pt(0, 0.1, 0.5) + "," + pt(40, 0.95, 0.1666666) + "]",
                    ),
                    row("I", "I", 0, "[" + pt(0, 0.8, 0.16) + "]"),
                    row("4th", "the 4th", 1, "[" + pt(0, 0.1, 0.5) + "," + pt(20, 0.2, 0.5) + "]"),
                )
                .joinToString("\n"),
        )
        val set = RealSwipes.load(rows, layout, keyboard, limit = 0) { it == "Atri" }
        assertThat(set.rows).isEqualTo(3)
        assertThat(set.taps).isEqualTo(1)
        assertThat(set.unswipeable).isEqualTo(1)
        val swipe = set.swipes.single()
        assertThat(swipe.target).isEqualTo("Atri")
        assertThat(swipe.context).isEqualTo("Brahmas son was ")
        assertThat(swipe.inDictionary).isTrue()
        val first = swipe.gesture.points.first()
        val a = keyboard.key('a')!!
        assertThat(first.x.toDouble()).isWithin(a.width * 0.01).of(a.centerX.toDouble())
        assertThat(first.y.toDouble()).isWithin(a.height * 0.01).of(a.centerY.toDouble())
        assertThat(swipe.gesture.points.last().timeMillis).isEqualTo(40)
    }

    private fun pt(t: Long, x: Double, y: Double) = """{"t": ${1000 + t}, "x": $x, "y": $y}"""

    private fun row(word: String, sentence: String, index: Int, data: String) =
        """{"word": "$word", "sentence": "$sentence", "word_idx": $index, "data": $data}"""
}

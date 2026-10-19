package dev.keycorrect.feature.keyboard

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.feature.keyboard.prediction.SlotKind
import dev.keycorrect.feature.keyboard.prediction.StripModel
import dev.keycorrect.feature.keyboard.prediction.StripSlot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The strip really draws its words (a measured but unplaced text node drew nothing on devices while
 * every JVM test passed, test.12 to test.15). Checks pixels, not semantics, because the slots
 * replace their semantics with labels.
 */
@RunWith(AndroidJUnit4::class)
class PredictionStripDrawTest {
    @get:Rule val compose = createComposeRule()

    private fun show(model: StripModel): ImageBitmap {
        compose.setContent {
            PredictionStrip(
                strip = { model },
                onSlot = {},
                onAddWord = {},
                modifier = Modifier.testTag("strip").width(360.dp).height(48.dp),
            )
        }
        return compose.onNodeWithTag("strip").captureToImage()
    }

    /** Pixels in the [third] (0..2) of [image] that differ clearly from its corner pixel. */
    private fun inkIn(image: ImageBitmap, third: Int): Int {
        val pixels = image.toPixelMap()
        val background = pixels[0, 0]
        val from = image.width * third / 3
        val to = image.width * (third + 1) / 3
        var ink = 0
        for (x in from until to) {
            for (y in 0 until image.height) {
                val p = pixels[x, y]
                val d =
                    kotlin.math.abs(p.red - background.red) +
                        kotlin.math.abs(p.green - background.green) +
                        kotlin.math.abs(p.blue - background.blue)
                if (d > INK_THRESHOLD) ink++
            }
        }
        return ink
    }

    @Test
    fun everySlotDrawsItsWord() {
        val image =
            show(
                StripModel(
                    left = StripSlot("hel", SlotKind.Literal),
                    centre = StripSlot("hello", SlotKind.Autocorrect, bold = true),
                    right = StripSlot("help", SlotKind.Suggestion),
                )
            )
        for (third in 0..2) assertThat(inkIn(image, third)).isGreaterThan(MIN_INK)
    }

    @Test
    fun aLongWordThatMustShrinkStillDraws() {
        val image =
            show(StripModel(centre = StripSlot("internationalisation", SlotKind.Suggestion)))
        assertThat(inkIn(image, 1)).isGreaterThan(MIN_INK)
    }

    @Test
    fun anEmptySlotDrawsNothing() {
        val image = show(StripModel(centre = StripSlot("hello", SlotKind.Suggestion)))
        assertThat(inkIn(image, 1)).isGreaterThan(MIN_INK)
        // Left and right have no words; the dividers/padding must not count as words.
        assertThat(inkIn(image, 0)).isLessThan(MIN_INK)
    }

    private companion object {
        const val INK_THRESHOLD = 0.6f
        const val MIN_INK = 20
    }
}

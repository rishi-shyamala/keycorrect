package dev.keycorrect.benchmark

import android.graphics.Paint
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.keycorrect.core.emoji.EmojiCatalog
import dev.keycorrect.feature.keyboard.emoji.EmojiGlyphFilter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opening the emoji panel the first time in a process: reading the shipped catalog, then hiding
 * what the device font can't draw. Both run once, off the main thread (AppEmojiEnvironment).
 */
@RunWith(AndroidJUnit4::class)
class EmojiBenchmark {
    @get:Rule val benchmarkRule = BenchmarkRule()

    private fun asset() =
        InstrumentationRegistry.getInstrumentation().context.assets.open(ASSET).readBytes()

    @Test
    fun readCatalog() {
        val bytes = asset()
        benchmarkRule.measureRepeated { EmojiCatalog.read(bytes.inputStream()) }
    }

    @Test
    fun filterByGlyph() {
        val catalog = EmojiCatalog.read(asset().inputStream())
        val paint = Paint()
        benchmarkRule.measureRepeated { EmojiGlyphFilter.filter(catalog, paint::hasGlyph) }
    }

    private companion object {
        const val ASSET = "emoji/emoji_en.kce"
    }
}

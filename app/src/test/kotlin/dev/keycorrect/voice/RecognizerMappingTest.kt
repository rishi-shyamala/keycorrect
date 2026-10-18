package dev.keycorrect.voice

import android.speech.SpeechRecognizer
import dev.keycorrect.core.voice.SttError
import dev.keycorrect.core.voice.SttResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognizerMappingTest {
    @Test
    fun partialTakesTheFirstNonBlankHypothesis() {
        assertEquals(
            SttResult.Partial("hello wor", 0),
            RecognizerMapping.partial(listOf(" ", " hello wor ")),
        )
        assertNull(RecognizerMapping.partial(null))
        assertNull(RecognizerMapping.partial(listOf("", "  ")))
    }

    @Test
    fun finalKeepsAlternativesBestFirstWithoutBlanksOrRepeats() {
        val result = RecognizerMapping.final(listOf("hello there", "", "hello there", "hello fair"))
        assertEquals(SttResult.Final("hello there", listOf("hello there", "hello fair")), result)
    }

    @Test
    fun emptyFinalIsNoMatch() {
        assertEquals(SttResult.Error(SttError.NoMatch), RecognizerMapping.final(emptyList()))
        assertEquals(SttResult.Error(SttError.NoMatch), RecognizerMapping.final(null))
    }

    @Test
    fun errorCodesMapToSttErrors() {
        assertEquals(SttError.NoMatch, RecognizerMapping.error(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(
            SttError.NoMatch,
            RecognizerMapping.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT),
        )
        assertEquals(SttError.Audio, RecognizerMapping.error(SpeechRecognizer.ERROR_AUDIO))
        assertEquals(
            SttError.NoPermission,
            RecognizerMapping.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS),
        )
        assertEquals(SttError.Busy, RecognizerMapping.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(SttError.Engine, RecognizerMapping.error(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(SttError.Engine, RecognizerMapping.error(SpeechRecognizer.ERROR_SERVER))
    }

    @Test
    fun levelsAreScaledToZeroToOne() {
        assertEquals(0f, RecognizerMapping.level(-10f), 0f)
        assertEquals(1f, RecognizerMapping.level(30f), 0f)
        assertTrue(RecognizerMapping.level(4f) in 0.1f..0.9f)
    }

    @Test
    fun captureLevelIsSilentForZerosAndLoudForFullScale() {
        assertEquals(0f, AudioCapture.level(ShortArray(480), 480), 0f)
        val loud = ShortArray(480) { if (it % 2 == 0) 30000 else -30000 }
        assertTrue(AudioCapture.level(loud, 480) > 0.9f)
        assertEquals(0f, AudioCapture.level(loud, 0), 0f)
    }
}

package dev.keycorrect.fieldlab

import android.content.Context
import android.content.res.Configuration
import dev.keycorrect.appContainer
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.DeviceClass
import dev.keycorrect.core.learning.InputProfile
import dev.keycorrect.core.learning.TouchSample
import dev.keycorrect.core.learning.WordCommitted
import dev.keycorrect.core.settings.SizeProfile
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val LETTERS = "qwertyuiopasdfghjklzxcvbnm"
private const val WORD_LENGTH = 5
private const val GEOMETRY_VERSION = "g2"
private const val PERCENT = 100
private const val SEED = 8
private const val GAUSS_TERMS = 12
private const val GAUSS_MEAN = 6f

/**
 * Debug only: feeds the learning model [words] synthetic words whose touches sit [biasY] key
 * heights low with a wide spread, for the current touch profile, so Settings > Keyboard fit has
 * data to show. Goes through the normal learning event path; no release code changes.
 */
internal fun seedFitData(context: Context, done: (String) -> Unit) {
    val words = 400
    val biasY = 0.25f
    val sigmaX = 0.18f
    val sigmaY = 0.3f
    val container = context.appContainer
    container.applicationScope.launch {
        val s = container.settingsRepository.settings.first()
        val config = context.resources.configuration
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        val size = SizeProfile.of(config.smallestScreenWidthDp, landscape)
        val sizes = s.sizeFor(size)
        val rows = if (sizes.numberRow) "+nr" else ""
        val bottom = (sizes.bottomKeyWidth * PERCENT).roundToInt()
        val layoutKey = "letters$rows@${sizes.keyHeightDp}dp/b$bottom/$GEOMETRY_VERSION"
        val profile =
            InputProfile(
                if (size.tablet) DeviceClass.Tablet else DeviceClass.Phone,
                landscape,
                layoutKey,
                FieldClass.Text,
            )
        val rnd = Random(SEED)
        val now = System.currentTimeMillis()
        repeat(words) { i ->
            val word = String(CharArray(WORD_LENGTH) { LETTERS[rnd.nextInt(LETTERS.length)] })
            val touches = word.map { c ->
                TouchSample(c, gauss(rnd) * sigmaX, biasY + gauss(rnd) * sigmaY)
            }
            container.learningHost.onEvent(
                WordCommitted(
                    timeMillis = now + i,
                    profile = profile,
                    context = emptyList(),
                    typed = word,
                    committed = word,
                    method = CommitMethod.Typed,
                    touches = touches,
                )
            )
        }
        done("Seeded $words words into ${profile.touchKey}")
    }
}

private fun gauss(rnd: Random): Float {
    var sum = 0f
    repeat(GAUSS_TERMS) { sum += rnd.nextFloat() }
    return sum - GAUSS_MEAN
}

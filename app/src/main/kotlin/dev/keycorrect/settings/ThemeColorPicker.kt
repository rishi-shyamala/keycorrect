package dev.keycorrect.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.keycorrect.R
import java.util.Locale

private val SWATCHES: List<Int> =
    listOf(
            0xFFFFFFFF,
            0xFFF2F2F5,
            0xFFD2D4DA,
            0xFF9AA0A6,
            0xFF5F6368,
            0xFF3A3B40,
            0xFF1F2023,
            0xFF000000,
            0xFFD93025,
            0xFFE8710A,
            0xFFF9AB00,
            0xFFFFD60A,
            0xFF188038,
            0xFF0B8043,
            0xFF12B5CB,
            0xFF1A73E8,
            0xFF1A6FE0,
            0xFF6AA8FF,
            0xFF673AB7,
            0xFFA142F4,
            0xFFE52592,
            0xFFFFB6C1,
            0xFFBFD3F7,
            0xFFC8E6C9,
        )
        .map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorPicker(current: Color, key: String, onPick: (Color) -> Unit) {
    val currentArgb = current.toArgb()
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (argb in SWATCHES) {
            val hex = hexOf(argb)
            val description = stringResource(R.string.theme_color_description, hex)
            val selected = argb == currentArgb
            Box(
                Modifier.size(SWATCH_SIZE)
                    .background(Color(argb), RoundedCornerShape(8.dp))
                    .border(
                        BorderStroke(
                            if (selected) 3.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        ),
                        RoundedCornerShape(8.dp),
                    )
                    .clickable(role = Role.RadioButton) { onPick(Color(argb)) }
                    .semantics {
                        contentDescription = description
                        this.selected = selected
                    }
            )
        }
    }
    HueBrightnessSliders(currentArgb, key, onPick)
}

@Composable
private fun HueBrightnessSliders(currentArgb: Int, key: String, onPick: (Color) -> Unit) {
    val hsv = remember(key, currentArgb) { hsvOf(currentArgb) }
    // A grey, white or black has no hue of its own: keep the one the sliders last had.
    val lastHue = remember(key) { floatArrayOf(hsv[0]) }
    val neutral = hsv[1] < NEUTRAL || hsv[2] < NEUTRAL
    if (!neutral) lastHue[0] = hsv[0]
    var hue by remember(key, currentArgb) { mutableFloatStateOf(lastHue[0]) }
    var value by remember(key, currentArgb) { mutableFloatStateOf(hsv[2]) }
    var saturation by remember(key, currentArgb) { mutableFloatStateOf(hsv[1]) }
    val hueLabel = stringResource(R.string.theme_hue)
    val brightnessLabel = stringResource(R.string.theme_brightness)
    fun picked() = Color.hsv(hue, saturation, value)
    Text(hueLabel, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
    Slider(
        value = hue,
        onValueChange = {
            hue = it
            // Moving the hue of a neutral colour is asking for a color; brightness never is.
            if (saturation < NEUTRAL) saturation = DEFAULT_SATURATION
        },
        onValueChangeFinished = { onPick(picked()) },
        valueRange = 0f..HUE_MAX,
        modifier =
            Modifier.padding(horizontal = 16.dp).semantics {
                contentDescription = hueLabel
                stateDescription = hue.toInt().toString()
            },
    )
    Text(brightnessLabel, modifier = Modifier.padding(start = 16.dp))
    Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { onPick(picked()) },
        valueRange = 0f..1f,
        modifier =
            Modifier.padding(horizontal = 16.dp).semantics {
                contentDescription = brightnessLabel
                stateDescription = "${(value * PERCENT).toInt()}%"
            },
    )
}

private const val RGB_MASK = 0xFFFFFF
private const val HUE_MAX = 360f
private const val PERCENT = 100
private val SWATCH_SIZE = 48.dp
private const val NEUTRAL = 0.02f
private const val DEFAULT_SATURATION = 0.8f

private fun hsvOf(argb: Int): FloatArray {
    val out = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, out)
    return out
}

private fun hexOf(argb: Int): String = String.format(Locale.US, "#%06X", argb and RGB_MASK)

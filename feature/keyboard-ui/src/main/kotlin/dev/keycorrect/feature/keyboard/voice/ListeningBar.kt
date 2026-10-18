package dev.keycorrect.feature.keyboard.voice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.keycorrect.core.voice.SttError
import dev.keycorrect.feature.keyboard.R

/**
 * The strip while voice typing is up (the keys stay visible below): a pulsing mic with a level
 * meter, "Listening..." or the text heard so far, Stop and a cross to cancel; or, after a failure,
 * a short message that goes away on its own or when tapped.
 */
@Composable
internal fun ListeningBar(
    voice: VoiceController,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme
    when (val state = voice.state) {
        is VoiceState.Listening ->
            Row(
                modifier = modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MicMeter(level = { voice.level }, tint = color.primary)
                val text =
                    when {
                        state.partial.isNotEmpty() -> state.partial
                        state.finishing -> stringResource(R.string.voice_finishing)
                        else -> stringResource(R.string.voice_listening)
                    }
                Text(
                    text,
                    modifier = Modifier.weight(1f),
                    color = color.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
                BarButton(
                    stringResource(R.string.voice_stop),
                    stringResource(R.string.voice_stop_description),
                    filled = true,
                ) {
                    onPress()
                    voice.finish()
                }
                BarButton("✕", stringResource(R.string.voice_cancel_description), filled = false) {
                    onPress()
                    voice.cancel()
                }
            }
        is VoiceState.Failed ->
            Box(
                modifier =
                    modifier
                        .clickable(
                            onClickLabel = stringResource(R.string.voice_error_dismiss_description),
                            onClick = voice::clearFailure,
                        )
                        .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    stringResource(errorMessage(state.error)),
                    color = color.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        VoiceState.Idle -> Unit
    }
}

internal fun errorMessage(error: SttError): Int =
    when (error) {
        SttError.NoPermission -> R.string.voice_error_no_permission
        SttError.NoEngine -> R.string.voice_error_no_engine
        SttError.Audio -> R.string.voice_error_audio
        SttError.Engine -> R.string.voice_error_engine
        SttError.NoMatch -> R.string.voice_error_no_match
        SttError.Busy -> R.string.voice_error_busy
    }

/** A pulsing mic dot with five bars that follow [level] (read in the draw phase only). */
@Composable
private fun MicMeter(level: () -> Float, tint: Color) {
    val pulse =
        rememberInfiniteTransition(label = "mic")
            .animateFloat(
                initialValue = 0.55f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
                label = "pulse",
            )
    val description = stringResource(R.string.voice_level_description)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Box(Modifier.size(10.dp).alpha(pulse.value)) {
            Canvas(Modifier.size(10.dp)) { drawCircle(tint) }
        }
        Canvas(Modifier.padding(start = 8.dp).size(width = 30.dp, height = 22.dp)) {
            val bars = METER_BARS
            val gap = size.width / (bars * 2f)
            val barWidth = gap
            val l = level()
            for (i in 0 until bars) {
                // Each bar lights at its own threshold; the middle ones are tallest.
                val lit = l > (i + 1f) / (bars + 1f)
                val h = size.height * (0.4f + 0.6f * (1f - kotlin.math.abs(i - 2) / 3f))
                drawRoundRect(
                    color = tint.copy(alpha = if (lit) 1f else 0.25f),
                    topLeft = Offset(i * 2 * gap, (size.height - h) / 2f),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }
    }
}

@Composable
private fun BarButton(label: String, description: String, filled: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme
    Box(
        modifier =
            Modifier.fillMaxHeight()
                .padding(vertical = 4.dp)
                .semantics { contentDescription = description }
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp),
            color = if (filled) color.primary else color.onSurfaceVariant,
        )
    }
}

private const val PULSE_MS = 700
private const val METER_BARS = 5

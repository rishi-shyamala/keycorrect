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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                MicMeter(
                    level = { voice.level },
                    fraction = { voice.phraseFraction },
                    speaking = { voice.speaking },
                    tint = color.primary,
                )
                Elapsed(voice)
                Words(voice, state, Modifier.weight(1f))
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
                            onClick = voice::onFailureTapped,
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

@Suppress("CyclomaticComplexMethod") // a flat mapping, one line per error
internal fun errorMessage(error: SttError): Int =
    when (error) {
        SttError.NoPermission -> R.string.voice_error_no_permission
        SttError.NoEngine -> R.string.voice_error_no_engine
        SttError.Audio -> R.string.voice_error_audio
        SttError.Engine -> R.string.voice_error_engine
        SttError.NoMatch -> R.string.voice_error_no_match
        SttError.Unclear -> R.string.voice_error_unclear
        SttError.Busy -> R.string.voice_error_busy
        SttError.NoVoicePack -> R.string.voice_error_no_voice_pack
        SttError.VoicePackUntrusted -> R.string.voice_error_voice_pack_untrusted
        SttError.VoicePackLibrariesMissing -> R.string.voice_error_voice_pack_libraries_missing
        SttError.VoicePackLoadFailed -> R.string.voice_error_voice_pack_load_failed
        SttError.VoicePackModelsMissing -> R.string.voice_error_voice_pack_models_missing
        SttError.VoicePackChanged -> R.string.voice_error_voice_pack_changed
        SttError.VoicePackNeedsRestart -> R.string.voice_error_voice_pack_needs_restart
        SttError.PrivateField -> R.string.voice_error_private_field
        SttError.Timeout -> R.string.voice_error_timeout
    }

/** The time since dictation started, m:ss; read here so only this text redraws each second. */
@Composable
private fun Elapsed(voice: VoiceController) {
    val countdown = voice.silenceCountdown
    val text =
        if (countdown > 0) stringResource(R.string.voice_auto_stop_countdown, countdown)
        else VoiceController.formatElapsed(voice.elapsedSeconds)
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color =
            if (countdown > 0) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/**
 * What was heard, the words that may still change greyed, and under it the hint to pause once the
 * phrase is long.
 */
@Composable
private fun Words(voice: VoiceController, state: VoiceState.Listening, modifier: Modifier) {
    val color = MaterialTheme.colorScheme
    val unstable = color.onSurface.copy(alpha = UNSTABLE_ALPHA)
    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        val body = MaterialTheme.typography.bodyMedium.copy(lineHeight = TEXT_LINE_HEIGHT)
        when {
            state.finishing ->
                Text(
                    stringResource(R.string.voice_finishing),
                    style = body,
                    color = color.onSurface,
                    maxLines = 1,
                )
            state.partial.isNotEmpty() -> {
                val cut = state.stableLength.coerceIn(0, state.partial.length)
                val text =
                    remember(state.partial, cut, unstable) {
                        buildAnnotatedString {
                            append(state.partial)
                            if (cut < state.partial.length) {
                                addStyle(SpanStyle(color = unstable), cut, state.partial.length)
                            }
                        }
                    }
                Text(
                    text,
                    style = body,
                    color = color.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
            else ->
                Text(
                    stringResource(
                        if (voice.starting) R.string.voice_starting else R.string.voice_listening
                    ),
                    style = body,
                    color = color.onSurface,
                    maxLines = 1,
                )
        }
        if (voice.phraseHint && !state.finishing) {
            Text(
                stringResource(R.string.voice_pause_hint),
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = HINT_LINE_HEIGHT),
                color = if (voice.phraseAmber) AMBER else color.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * A mic with a ring that fills as the phrase nears the engine's limit (amber near the end), a dot
 * that pulses while speech is heard, and five bars that follow [level]. The ring and the level are
 * read in the draw phase only, so the five updates a second redraw this canvas and recompose
 * nothing; only [speaking] (which starts and stops the pulse) is read in composition.
 */
@Composable
private fun MicMeter(
    level: () -> Float,
    fraction: () -> Float,
    speaking: () -> Boolean,
    tint: Color,
) {
    // The transition runs only while speech is heard (it changes a few times a minute at most);
    // otherwise nothing animates and nothing redraws between the progress updates.
    val pulse: State<Float>? =
        if (speaking()) {
            rememberInfiniteTransition(label = "mic")
                .animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
                    label = "pulse",
                )
        } else {
            null
        }
    val description = stringResource(R.string.voice_level_description)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Canvas(Modifier.size(RING_SIZE)) {
            drawRing(tint, fraction())
            drawCircle(
                tint,
                radius = size.minDimension / DOT_DIVISOR,
                alpha = pulse?.value ?: IDLE_DOT_ALPHA,
            )
        }
        Canvas(Modifier.padding(start = 6.dp).size(width = 30.dp, height = 22.dp)) {
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

/** The ring around the mic: a faint track, and the phrase's share of it (amber near the end). */
private fun DrawScope.drawRing(tint: Color, fraction: Float) {
    val stroke = RING_STROKE.toPx()
    val topLeft = Offset(stroke / 2f, stroke / 2f)
    val arc = Size(size.width - stroke, size.height - stroke)
    drawArc(tint.copy(alpha = 0.2f), 0f, FULL_CIRCLE, false, topLeft, arc, style = Stroke(stroke))
    if (fraction <= 0f) return
    drawArc(
        color = if (fraction >= VoiceController.AMBER_FROM) AMBER else tint,
        startAngle = -QUARTER_CIRCLE,
        sweepAngle = FULL_CIRCLE * fraction,
        useCenter = false,
        topLeft = topLeft,
        size = arc,
        style = Stroke(stroke, cap = StrokeCap.Round),
    )
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
private const val UNSTABLE_ALPHA = 0.55f
private const val IDLE_DOT_ALPHA = 0.7f
private const val FULL_CIRCLE = 360f
private const val QUARTER_CIRCLE = 90f
private const val DOT_DIVISOR = 4f
private val AMBER = Color(0xFFFFB300)
private val RING_SIZE = 28.dp
private val RING_STROKE = 3.dp
private val TEXT_LINE_HEIGHT = 17.sp
private val HINT_LINE_HEIGHT = 12.sp

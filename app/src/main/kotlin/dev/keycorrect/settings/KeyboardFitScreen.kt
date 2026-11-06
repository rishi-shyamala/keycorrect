@file:Suppress("TooManyFunctions") // One small composable per part of the screen.

package dev.keycorrect.settings

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.keycorrect.AppContainer
import dev.keycorrect.R
import dev.keycorrect.core.learning.fit.FitKind
import dev.keycorrect.core.learning.fit.FitRecommendation
import dev.keycorrect.core.learning.fit.FitSizes
import dev.keycorrect.core.learning.fit.KeyboardFit
import dev.keycorrect.core.learning.model.KeyTouchStatistics
import dev.keycorrect.core.learning.model.ProfileTouchStatistics
import dev.keycorrect.core.learning.model.TouchStatistics
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.SizeProfile
import dev.keycorrect.core.settings.SizeSettings
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.flow

private const val PERCENT = 100
private val LetterRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val RowIndent = listOf(0f, 0.5f, 1.5f)
private const val SPREAD_GOOD = 0.15f
private const val SPREAD_OK = 0.25f
private const val PREVIEW_ROWS = 3
private const val HEAT_ROW_DP = 44
private const val OUTLINE_WIDTH = 1.5f

private val SizesSaver =
    listSaver<SizeSettings?, Any>(
        save = { s ->
            if (s == null) emptyList()
            else
                listOf(
                    s.keyHeightDp,
                    s.bottomKeyWidth,
                    s.modeEnterKeyWidth,
                    s.edgePaddingDp,
                    s.keyGapDp,
                    s.numberRow,
                )
        },
        restore = { l ->
            if (l.size < 6) null
            else
                SizeSettings(
                    l[0] as Int,
                    l[1] as Float,
                    l[2] as Float,
                    l[3] as Int,
                    l[4] as Int,
                    l[5] as Boolean,
                )
        },
    )

/**
 * "Keyboard fit" (docs/PHASE8.md, P8-6): a heat map of where taps land on the letter keys, the
 * evidence behind it, and, when worthwhile, one suggested size change with a preview. Apply never
 * happens automatically; Undo restores the previous sizes for as long as the screen state lives.
 */
@Composable
fun KeyboardFitScreen(
    contentPadding: PaddingValues,
    container: AppContainer,
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    SettingsPage(contentPadding) {
        Text(
            stringResource(R.string.fit_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LearningDataGate(container, inline = true, onResetFailed = {}) { host ->
            val state = rememberLoadState(0) { flow { emit(host.touchStatistics()) } }
            when (state) {
                is LoadState.Loaded -> FitBody(state.value, settings, onUpdate)
                is LoadState.Failed ->
                    Text(
                        stringResource(R.string.learning_load_failed),
                        modifier = Modifier.padding(16.dp),
                    )
                LoadState.Loading -> Unit
            }
        }
    }
}

@Composable
private fun currentProfile(): SizeProfile {
    val config = LocalConfiguration.current
    return SizeProfile.of(
        config.smallestScreenWidthDp,
        config.orientation == Configuration.ORIENTATION_LANDSCAPE,
    )
}

@Suppress("LongMethod") // A flat column of the screen's parts.
@Composable
private fun FitBody(
    touch: TouchStatistics,
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    val here = currentProfile()
    val config = LocalConfiguration.current
    val picked = pickTouchProfile(touch.profiles, here, settings.sizeFor(here).keyHeightDp)
    if (picked == null) {
        Text(stringResource(R.string.fit_none), modifier = Modifier.padding(16.dp))
        return
    }
    val info = parseTouchKey(picked.touchKey)
    val target = info.sizeProfile ?: here
    val sizes = settings.sizeFor(target)
    val current = sizes.toFitSizes()
    val recorded = current.copy(keyHeightDp = info.recordedHeightDp ?: current.keyHeightDp)
    val widthDp = (if (target == here) config.screenWidthDp else typicalWidthDp(target)).toDouble()
    var previous by
        rememberSaveable(stateSaver = SizesSaver) { mutableStateOf<SizeSettings?>(null) }

    Text(
        stringResource(R.string.fit_editing, stringResource(target.labelRes())),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
    )
    Text(
        stringResource(R.string.fit_touch_profile, touchProfileLabel(picked.touchKey)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )

    SectionHeader(stringResource(R.string.fit_heat_title))
    HeatMap(picked)
    val resources = LocalResources.current
    Text(
        resources.getQuantityString(
            R.plurals.fit_evidence,
            picked.wordCount,
            picked.wordCount,
            picked.sampleCount,
        ),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    Text(
        stringResource(R.string.fit_heat_legend),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )

    SectionHeader(stringResource(R.string.fit_suggestion_title))
    val saved = previous
    if (saved != null) {
        AppliedCard(
            onUndo = {
                onUpdate { it.withSizes(target, saved) }
                previous = null
            }
        )
        return
    }
    val dismissed =
        KeyboardFit.isDismissed(settings.fitDismissedAt(target), System.currentTimeMillis())
    val rec =
        remember(picked, widthDp, current, recorded) {
            KeyboardFit.recommend(picked, widthDp, current, FitSettingLimits, recorded)
        }
    when {
        picked.wordCount < KeyboardFit.MIN_WORDS ->
            Note(
                stringResource(
                    R.string.fit_not_enough,
                    KeyboardFit.MIN_WORDS,
                    picked.wordCount,
                )
            )
        dismissed -> Note(stringResource(R.string.fit_dismissed))
        rec == null -> Note(stringResource(R.string.fit_no_change))
        else ->
            SuggestionCard(
                rec = rec,
                current = current,
                onApply = {
                    previous = sizes
                    onUpdate { it.updateSizes(target) { s -> s.withFitSizes(rec.proposed) } }
                },
                onNotNow = {
                    val now = System.currentTimeMillis()
                    onUpdate { it.withFitDismissed(target, now) }
                },
            )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun AppliedCard(onUndo: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.fit_applied),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            OutlinedButton(onClick = onUndo) { Text(stringResource(R.string.fit_undo)) }
        }
    }
}

@Composable
private fun SuggestionCard(
    rec: FitRecommendation,
    current: FitSizes,
    onApply: () -> Unit,
    onNotNow: () -> Unit,
) {
    val text =
        when (rec.kind) {
            FitKind.KEY_HEIGHT ->
                stringResource(R.string.fit_suggest_height, rec.percent, rec.relativePercent)
            FitKind.KEY_GAP ->
                stringResource(R.string.fit_suggest_gap, -rec.percent, rec.relativePercent)
            FitKind.EDGE_PADDING ->
                stringResource(R.string.fit_suggest_edge, -rec.percent, rec.relativePercent)
        }
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    R.string.fit_estimate,
                    percentLabel(rec.missBefore),
                    percentLabel(rec.missAfter),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PreviewPair(current, rec.proposed)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApply) { Text(stringResource(R.string.fit_apply)) }
                TextButton(onClick = onNotNow) { Text(stringResource(R.string.fit_not_now)) }
            }
        }
    }
}

private fun percentLabel(rate: Double): String =
    "%.1f%%".format(java.util.Locale.getDefault(), rate * PERCENT)

/** Two simple keyboards side by side: today's and the proposed one, to scale. */
@Composable
private fun PreviewPair(now: FitSizes, proposed: FitSizes) {
    val description =
        stringResource(
            R.string.fit_preview_description,
            now.keyHeightDp,
            proposed.keyHeightDp,
            proposed.keyGapDp,
            proposed.edgePaddingDp,
        )
    // The canvas draws at half scale: frame height is (rows * keyHeight + 2 * padding) / 2.
    val maxHeight =
        (maxOf(now.keyHeightDp, proposed.keyHeightDp) * PREVIEW_ROWS +
            2 * maxOf(now.edgePaddingDp, proposed.edgePaddingDp)) / 2 + 2
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    ) {
        PreviewKeyboard(
            stringResource(R.string.fit_preview_now),
            now,
            maxHeight,
            Modifier.weight(1f),
        )
        PreviewKeyboard(
            stringResource(R.string.fit_preview_proposed),
            proposed,
            maxHeight,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun PreviewKeyboard(label: String, sizes: FitSizes, maxHeightDp: Int, modifier: Modifier) {
    val key = MaterialTheme.colorScheme.secondaryContainer
    val frame = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Canvas(modifier = Modifier.fillMaxWidth().height(maxHeightDp.dp)) {
            // Shrink the real dp sizes so the keyboard fits the column, keeping the proportions.
            val scale = 0.5f * density
            val rowH = sizes.keyHeightDp * scale
            val pad = sizes.edgePaddingDp * scale
            val gap = sizes.keyGapDp * scale
            val cell = (size.width - 2 * pad) / LetterRows[0].length
            drawRect(
                frame,
                size = Size(size.width, rowH * PREVIEW_ROWS + 2 * pad),
                style = Stroke(1f),
            )
            LetterRows.forEachIndexed { r, letters ->
                letters.indices.forEach { c ->
                    val x = pad + (RowIndent[r] + c) * cell + gap / 2
                    val y = pad + r * rowH + gap / 2
                    drawRect(
                        key,
                        topLeft = Offset(x, y),
                        size = Size((cell - gap).coerceAtLeast(1f), (rowH - gap).coerceAtLeast(1f)),
                    )
                }
            }
        }
    }
}

private fun spreadOf(k: KeyTouchStatistics) = (k.sigmaX + k.sigmaY) / 2f

/** Letter keys as cells; each shows the mean offset as a dot and the spread as an oval. */
@Composable
private fun HeatMap(profile: ProfileTouchStatistics) {
    val byChar = profile.keys.associateBy { it.char }
    val good = MaterialTheme.colorScheme.primary
    val ok = MaterialTheme.colorScheme.tertiary
    val bad = MaterialTheme.colorScheme.error
    val outline = MaterialTheme.colorScheme.outlineVariant
    val resources = LocalResources.current
    fun pct(v: Float) = (kotlin.math.abs(v) * PERCENT).roundToInt()
    val description =
        stringResource(
            R.string.fit_heat_description,
            pct(profile.meanOffsetX),
            pct(profile.meanOffsetY),
            pct(profile.sigmaX),
            pct(profile.sigmaY),
        ) +
            " " +
            LetterRows.joinToString(" ") { row ->
                row.mapNotNull { ch ->
                        byChar[ch]?.let {
                            resources.getString(
                                R.string.fit_heat_key_description,
                                ch.toString(),
                                pct(spreadOf(it)),
                            )
                        }
                    }
                    .joinToString(". ")
            }
    Canvas(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height((HEAT_ROW_DP * LetterRows.size).dp)
                .semantics { contentDescription = description }
    ) {
        val cell = size.width / LetterRows[0].length
        val rowH = size.height / LetterRows.size
        LetterRows.forEachIndexed { r, letters ->
            letters.forEachIndexed { c, ch ->
                val left = (RowIndent[r] + c) * cell
                val top = r * rowH
                drawRect(outline, Offset(left, top), Size(cell, rowH), style = Stroke(1f))
                val k = byChar[ch] ?: return@forEachIndexed
                val colour =
                    when {
                        spreadOf(k) < SPREAD_GOOD -> good
                        spreadOf(k) < SPREAD_OK -> ok
                        else -> bad
                    }
                drawTouch(k, Offset(left, top), cell, rowH, colour)
            }
        }
    }
}

private fun DrawScope.drawTouch(
    k: KeyTouchStatistics,
    topLeft: Offset,
    cellW: Float,
    cellH: Float,
    colour: Color,
) {
    val cx = topLeft.x + cellW / 2 + k.meanDx.coerceIn(-1f, 1f) * cellW
    val cy = topLeft.y + cellH / 2 + k.meanDy.coerceIn(-1f, 1f) * cellH
    val rx = (k.sigmaX * cellW).coerceAtLeast(1f)
    val ry = (k.sigmaY * cellH).coerceAtLeast(1f)
    drawOval(colour.copy(alpha = 0.25f), Offset(cx - rx, cy - ry), Size(2 * rx, 2 * ry))
    drawOval(colour, Offset(cx - rx, cy - ry), Size(2 * rx, 2 * ry), style = Stroke(OUTLINE_WIDTH))
    drawCircle(colour, radius = 2.5f * density, center = Offset(cx, cy))
}

@Composable
private fun touchProfileLabel(touchKey: String): String {
    val parts = touchKey.split('/', limit = 3)
    val device =
        when (parts.getOrNull(0)) {
            "Phone" -> stringResource(R.string.learning_profile_phone)
            "Tablet" -> stringResource(R.string.learning_profile_tablet)
            "HardwareKeyboard" -> stringResource(R.string.learning_profile_hardware)
            else -> null
        }
    val orientation =
        when (parts.getOrNull(1)) {
            "land" -> stringResource(R.string.learning_profile_landscape)
            "port" -> stringResource(R.string.learning_profile_portrait)
            else -> null
        }
    return if (device != null && orientation != null)
        stringResource(R.string.learning_profile_label, device, orientation)
    else touchKey
}

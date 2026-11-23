package dev.keycorrect.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.keycorrect.R

/**
 * Open-source licences and required attributions: the app's own licence, the dictionary data
 * sources (CC BY-SA 4.0 / CC BY 2.0 FR need visible attribution) and the main libraries.
 * Attribution text mirrors docs/DATA_SOURCES.md and LICENSE-DATA.md.
 */
@Composable
fun LicensesScreen(contentPadding: PaddingValues) {
    SettingsPage(contentPadding) {
        SectionHeader(stringResource(R.string.licenses_app_header))
        Body(R.string.licenses_app_body)

        SectionHeader(stringResource(R.string.licenses_data_header))
        Body(R.string.licenses_data_attribution)
        Body(R.string.licenses_data_derived)
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.licenses_data_aosp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.licenses_data_wikipedia),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.licenses_data_tatoeba),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SectionHeader(stringResource(R.string.licenses_libraries_header))
        Body(R.string.licenses_androidx)
        Body(R.string.licenses_kotlin)
        Body(R.string.licenses_sqlcipher)
        Body(R.string.licenses_sherpa)
        Body(R.string.licenses_onnxruntime)
        Body(R.string.licenses_moonshine)
        Body(R.string.licenses_silero)
        Body(R.string.licenses_executorch)

        FutoSection()
    }
}

/**
 * The FUTO Swipe credit and, with the swipe pack installed, its full licence text (the pack's own
 * `LICENSE.md`, read from the signed pack).
 */
@Composable
private fun FutoSection() {
    val pack = rememberSwipePackState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionHeader(stringResource(R.string.licenses_futo_header))
    Body(R.string.licenses_futo_body)
    val license = (pack as? SwipePackState.Installed)?.license
    if (license == null) {
        if (pack !is SwipePackState.Checking) Body(R.string.licenses_futo_not_installed)
        return
    }
    NavRow(
        title =
            stringResource(
                if (expanded) R.string.licenses_futo_hide else R.string.licenses_futo_show
            ),
        onClick = { expanded = !expanded },
    )
    if (expanded) {
        Text(
            license,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Body(@StringRes textRes: Int) {
    Text(
        stringResource(textRes),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

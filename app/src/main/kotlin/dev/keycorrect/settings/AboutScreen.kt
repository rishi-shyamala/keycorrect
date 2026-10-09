package dev.keycorrect.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.keycorrect.BuildConfig
import dev.keycorrect.R

@Composable
fun AboutScreen(contentPadding: PaddingValues, onNavigate: (SettingsDestination) -> Unit) {
    val context = LocalContext.current
    val version = remember(context) { context.versionName() }
    SettingsPage(contentPadding) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    stringResource(R.string.about_version, version),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.about_license),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.about_privacy),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (BuildConfig.DEBUG) {
            NavRow(
                title = stringResource(R.string.about_autofill_test),
                supportingText = stringResource(R.string.about_autofill_test_summary),
                onClick = { onNavigate(SettingsDestination.AutofillTest) },
            )
        }
    }
}

@Suppress("DEPRECATION") // The flags overload needs API 33; minSdk is 31.
private fun Context.versionName(): String =
    try {
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                packageManager.getPackageInfo(packageName, 0)
            }
        info.versionName.orEmpty()
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }

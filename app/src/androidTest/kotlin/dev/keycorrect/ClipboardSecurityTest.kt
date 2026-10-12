package dev.keycorrect

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.ime.ClipImageProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * The exit criterion of PHASE4 PR C: other apps cannot read the stored clipboard data. The manifest
 * checks run against the installed package; the storage checks use the real Keystore.
 */
class ClipboardSecurityTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val createdClips = mutableListOf<Long>()

    @After
    fun cleanUp() = runBlocking {
        if (createdClips.isNotEmpty()) context.appContainer.clipboardRepository.delete(createdClips)
    }

    private fun packageInfo(): PackageInfo =
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_ACTIVITIES or
                        PackageManager.GET_SERVICES or
                        PackageManager.GET_RECEIVERS or
                        PackageManager.GET_PROVIDERS)
                    .toLong()
            ),
        )

    @Test
    fun noContentProviderIsExported() {
        val providers = packageInfo().providers.orEmpty()
        assertThat(providers.map { it.name }).contains(ClipImageProvider::class.java.name)
        assertThat(providers.filter { it.exported }.map { it.name }).isEmpty()
        val clips = providers.single { it.name == ClipImageProvider::class.java.name }
        assertThat(clips.authority).isEqualTo(context.packageName + ".clips")
        assertThat(clips.grantUriPermissions).isTrue()
    }

    @Test
    fun onlyTheLauncherActivitiesAndTheImeAreExported() {
        val info = packageInfo()
        val launcher =
            context.packageManager
                .queryIntentActivities(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(context.packageName),
                    PackageManager.ResolveInfoFlags.of(0),
                )
                .map { it.activityInfo.name }
        assertThat(launcher).isNotEmpty()
        // Compose's preview activity comes from ui-tooling, a debug-only dependency with no data.
        val debugOnly = if (BuildConfig.DEBUG) setOf(COMPOSE_PREVIEW_ACTIVITY) else emptySet()
        assertThat(
                info.activities
                    .orEmpty()
                    .filter { it.exported && it.name !in debugOnly }
                    .map { it.name }
            )
            .containsExactlyElementsIn(launcher)

        val services = info.services.orEmpty().filter { it.exported }
        assertThat(services.map { it.name })
            .containsExactly("dev.keycorrect.ime.KeyCorrectImeService")
        assertThat(services.single().permission).isEqualTo("android.permission.BIND_INPUT_METHOD")

        // Exported receivers only behind a permission apps can't hold (androidx.profileinstaller's
        // receiver requires DUMP, which only the shell and the system have).
        for (receiver in info.receivers.orEmpty().filter { it.exported }) {
            assertThat(receiver.permission).isIn(SYSTEM_ONLY_PERMISSIONS)
        }
    }

    @Test
    fun backupIsDisabled() {
        assertThat(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP).isEqualTo(0)
    }

    @Test
    fun imageBlobsAreEncryptedOnDiskAndServedDecryptedByTheProvider() = runBlocking {
        val repository = context.appContainer.clipboardRepository
        val marker = "KEYCORRECT-SECURITY-TEST-IMAGE-".toByteArray()
        val image = ByteArray(4_096) { marker[it % marker.size] }

        val id = repository.insertImage(image, "image/png")
        createdClips += id
        val clip = repository.clips().single { it.id == id }
        val name = checkNotNull(clip.imageFile)

        val raw = File(File(context.noBackupFilesDir, "clips"), name).readBytes()
        assertThat(String(raw, Charsets.ISO_8859_1))
            .doesNotContain(String(marker, Charsets.ISO_8859_1))

        val uri = ClipImageProvider.uriFor(context, name)
        assertThat(context.contentResolver.getType(uri)).isEqualTo("image/png")
        val served = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertThat(served).isEqualTo(image)
    }

    @Test
    fun theProviderRefusesUnknownAndMalformedNames() {
        val bad =
            listOf(
                "00000000-0000-0000-0000-000000000000", // well-formed, no such clip
                "..",
                "../databases/keycorrect.db",
                "x",
            )
        for (name in bad) {
            val uri = ClipImageProvider.uriFor(context, name)
            assertThat(
                    runCatching { context.contentResolver.openInputStream(uri)?.close() }.isFailure
                )
                .isTrue()
            assertThat(context.contentResolver.getType(uri)).isNull()
        }
    }

    private companion object {
        const val COMPOSE_PREVIEW_ACTIVITY = "androidx.compose.ui.tooling.PreviewActivity"
        val SYSTEM_ONLY_PERMISSIONS = setOf("android.permission.DUMP")
    }
}

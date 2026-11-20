package dev.keycorrect.security

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards docs/PLAN.md section 15 against the merged release manifest: no INTERNET, no exported
 * component beyond the IME service and the launcher activity, no backup, no direct boot. A library
 * upgrade that adds one fails here instead of shipping (docs/SECURITY.md).
 */
class ManifestSecurityTest {
    private val PROFILE_INSTALLER = "androidx.profileinstaller.ProfileInstallReceiver"
    private val android = "http://schemas.android.com/apk/res/android"

    private fun load(property: String): Element {
        val path = checkNotNull(System.getProperty(property)) { "$property not set" }
        val doc =
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(File(path))
        return doc.documentElement
    }

    private fun Element.attr(name: String): String? =
        getAttributeNS(android, name).takeIf { it.isNotEmpty() }

    private fun Element.all(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.components(): List<Element> =
        listOf("activity", "activity-alias", "service", "receiver", "provider").flatMap { all(it) }

    private fun Element.permissions() = all("uses-permission").mapNotNull { it.attr("name") }

    private fun Element.application() = all("application").single()

    @Test
    fun appRequestsOnlyApprovedPermissions() {
        val permissions = load("kc.manifest.app").permissions()
        assertThat(permissions)
            .containsExactly(
                "android.permission.VIBRATE",
                "android.permission.RECORD_AUDIO",
                // Added by androidx.core; signature level, guards our own dynamic receivers.
                "dev.keycorrect.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            )
        assertThat(permissions).doesNotContain("android.permission.INTERNET")
    }

    @Test
    fun appExportsOnlyTheImeServiceTheLauncherActivityAndTheProfileInstaller() {
        val exported = load("kc.manifest.app").components().filter { it.attr("exported") == "true" }
        assertThat(exported.map { it.attr("name") })
            .containsExactly(
                "dev.keycorrect.ime.KeyCorrectImeService",
                "dev.keycorrect.settings.SettingsActivity",
                PROFILE_INSTALLER,
            )
        val ime = exported.single { it.attr("name") == "dev.keycorrect.ime.KeyCorrectImeService" }
        assertThat(ime.attr("permission")).isEqualTo("android.permission.BIND_INPUT_METHOD")
        // Baseline profiles (adb, Macrobenchmark): only the shell/system may call it.
        val installer = exported.single { it.attr("name") == PROFILE_INSTALLER }
        assertThat(installer.attr("permission")).isEqualTo("android.permission.DUMP")
    }

    @Test
    fun everyComponentStatesExportedExplicitly() {
        for (component in load("kc.manifest.app").components()) {
            assertThat(component.attr("exported")).isNotNull()
        }
    }

    @Test
    fun appHasNoBackupAndNoDirectBoot() {
        val app = load("kc.manifest.app")
        val application = app.application()
        assertThat(application.attr("allowBackup")).isEqualTo("false")
        assertThat(application.attr("dataExtractionRules")).isNotNull()
        assertThat(application.attr("debuggable")).isNotEqualTo("true")
        assertThat(application.attr("usesCleartextTraffic")).isNotEqualTo("true")
        assertThat(application.attr("directBootAware")).isNotEqualTo("true")
        for (component in app.components()) {
            assertThat(component.attr("directBootAware")).isNotEqualTo("true")
        }
    }

    @Test
    fun providersAreNeverExported() {
        for (provider in load("kc.manifest.app").all("provider")) {
            assertThat(provider.attr("exported")).isEqualTo("false")
        }
    }

    @Test
    fun noDebugToolsInRelease() {
        val names = load("kc.manifest.app").components().map { it.attr("name").orEmpty() }
        assertThat(names.filter { it.contains("fieldlab", ignoreCase = true) }).isEmpty()
    }

    @Test
    fun packsHaveNoPermissionsNoComponentsAndNoBackup() {
        for (pack in listOf("kc.manifest.voicepack", "kc.manifest.neuralpack")) {
            val manifest = load(pack)
            assertThat(manifest.permissions()).isEmpty()
            assertThat(manifest.components()).isEmpty()
            assertThat(manifest.application().attr("allowBackup")).isEqualTo("false")
        }
    }

    @Test
    fun samplePluginHasNoPermissionsAndOnlyItsEngineService() {
        val manifest = load("kc.manifest.sampleplugin")
        assertThat(manifest.permissions()).isEmpty()
        assertThat(manifest.components().map { it.attr("name") })
            .containsExactly(".SampleEngineService")
    }
}

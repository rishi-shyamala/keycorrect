package dev.keycorrect.feature.voicesherpa

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoicePackLocatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeAssets(var complete: Boolean = true) : PackAssets {
        override val manager
            get() = error("not read in these tests")

        override fun hasModels() = complete
    }

    private class FakeSource(var libDir: String?) : PackSource {
        var installed = true
        var stamp = PackStamp(1, 100)
        var packSigners: Set<String>? = setOf("aa")
        var own: Set<String> = setOf("aa")
        var assets: FakeAssets? = FakeAssets()
        var signerLookups = 0
        var assetLookups = 0

        /** Runs inside [assets], i.e. between the first lookup and the last. */
        var onAssets: () -> Unit = {}

        override fun pack(withSigners: Boolean): PackFacts? {
            if (!installed) return null
            if (withSigners) signerLookups++
            return PackFacts(stamp, "1.0", if (withSigners) packSigners else null, libDir)
        }

        override fun ownSigners() = own

        override fun assets(): PackAssets? {
            assetLookups++
            onAssets()
            return if (installed) assets else null
        }
    }

    private val loaded = mutableListOf<String>()
    private lateinit var libs: File
    private lateinit var source: FakeSource
    private lateinit var locator: VoicePackLocator

    @Before
    fun setUp() {
        VoicePackLocator.resetLoadedForTest()
        libs = tmp.newFolder("lib")
        VoicePackLayout.LIBRARIES.forEach { File(libs, it).writeText("x") }
        source = FakeSource(libs.path)
        locator = VoicePackLocator(source) { loaded += it }
    }

    @After fun tearDown() = VoicePackLocator.resetLoadedForTest()

    private fun problem() = (locator.locate() as VoicePackResult.Unavailable).problem

    @Test
    fun aVerifiedPackIsAvailableAndLoadsOnnxRuntimeFirst() {
        assertThat(locator.locate()).isInstanceOf(VoicePackResult.Available::class.java)
        assertThat(loaded.map { File(it).name })
            .containsExactly("libonnxruntime.so", "libsherpa-onnx-jni.so")
            .inOrder()
    }

    @Test
    fun notInstalled() {
        source.installed = false
        assertThat(problem()).isEqualTo(VoicePackProblem.NotInstalled)
        assertThat(loaded).isEmpty()
    }

    @Test
    fun aDifferentSignerIsRefusedBeforeAnythingIsLoaded() {
        source.packSigners = setOf("bb")
        assertThat(problem()).isEqualTo(VoicePackProblem.SignatureMismatch)
        assertThat(loaded).isEmpty()
        source.packSigners = null
        assertThat(problem()).isEqualTo(VoicePackProblem.SignatureMismatch)
    }

    @Test
    fun theAssetsAreTakenBeforeTheSignatureCheck() {
        var signersChecked = 0
        source.onAssets = { signersChecked = source.signerLookups }
        locator.locate()
        // The package was read (with signers) once before the assets; no second signing lookup.
        assertThat(signersChecked).isEqualTo(1)
        assertThat(source.assetLookups).isEqualTo(1)
    }

    @Test
    fun missingLibrariesOrModelsAreBroken() {
        File(libs, "libsherpa-onnx-jni.so").delete()
        assertThat(problem()).isEqualTo(VoicePackProblem.Broken)
        assertThat(loaded).isEmpty()
        File(libs, "libsherpa-onnx-jni.so").writeText("x")
        source.assets = FakeAssets(complete = false)
        assertThat(problem()).isEqualTo(VoicePackProblem.Broken)
    }

    @Test
    fun anUnloadableLibraryIsBroken() {
        locator = VoicePackLocator(source) { throw UnsatisfiedLinkError() }
        assertThat(problem()).isEqualTo(VoicePackProblem.Broken)
    }

    @Test
    fun aPackReplacedWhileLoadingIsRefused() {
        // The pack is updated between the first read and the check after loading.
        var reads = 0
        val changing =
            object : PackSource by source {
                override fun pack(withSigners: Boolean): PackFacts? {
                    if (++reads == 2) source.stamp = PackStamp(2, 200)
                    return source.pack(withSigners)
                }
            }
        locator = VoicePackLocator(changing) { loaded += it }
        assertThat(problem()).isEqualTo(VoicePackProblem.Broken)
    }

    @Test
    fun theAvailableResultIsCachedPerInstallAndRefreshedAfterAnUpdate() {
        val first = locator.locate() as VoicePackResult.Available
        val lookups = source.signerLookups
        val assetLookups = source.assetLookups
        val second = locator.locate() as VoicePackResult.Available
        assertThat(second.pack).isSameInstanceAs(first.pack)
        assertThat(source.signerLookups).isEqualTo(lookups)
        assertThat(source.assetLookups).isEqualTo(assetLookups)

        // Updated: checked again (the new install is in the same directory, so it loads).
        source.stamp = PackStamp(2, 200)
        val third = locator.locate() as VoicePackResult.Available
        assertThat(third.pack).isNotSameInstanceAs(first.pack)
        assertThat(source.signerLookups).isEqualTo(lookups + 1)
    }

    @Test
    fun aCachedPackThatWasUninstalledOrReSignedIsNotServed() {
        locator.locate()
        source.installed = false
        assertThat(problem()).isEqualTo(VoicePackProblem.NotInstalled)
        source.installed = true
        source.stamp = PackStamp(3, 300)
        source.packSigners = setOf("evil")
        assertThat(problem()).isEqualTo(VoicePackProblem.SignatureMismatch)
    }

    @Test
    fun anUpdateThatMovesTheLibrariesIsBrokenUntilRestart() {
        locator.locate()
        val moved = tmp.newFolder("lib2")
        VoicePackLayout.LIBRARIES.forEach { File(moved, it).writeText("x") }
        source.libDir = moved.path
        source.stamp = PackStamp(2, 200)
        assertThat(problem()).isEqualTo(VoicePackProblem.Broken)
    }
}

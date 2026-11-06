package dev.keycorrect.core.packs

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SignedPackLocatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeAssets(var complete: Boolean = true) : PackAssets {
        override val manager
            get() = error("not read in these tests")

        override fun hasAssets() = complete
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

    private val spec = PackSpec("test.pack", listOf("libbase.so", "libjni.so"), listOf("data.bin"))
    private val loaded = mutableListOf<String>()
    private lateinit var libs: File
    private lateinit var source: FakeSource
    private lateinit var locator: SignedPackLocator

    @Before
    fun setUp() {
        SignedPackLocator.resetLoadedForTest()
        libs = tmp.newFolder("lib")
        spec.libraries.forEach { File(libs, it).writeText("x") }
        source = FakeSource(libs.path)
        locator = SignedPackLocator(spec, source) { loaded += it }
    }

    @After fun tearDown() = SignedPackLocator.resetLoadedForTest()

    private fun problem() = (locator.locate() as PackResult.Unavailable).problem

    @Test
    fun aVerifiedPackIsAvailableAndLoadsItsLibrariesInTheSpecsOrder() {
        assertThat(locator.locate()).isInstanceOf(PackResult.Available::class.java)
        assertThat(loaded.map { File(it).name })
            .containsExactly("libbase.so", "libjni.so")
            .inOrder()
    }

    @Test
    fun notInstalled() {
        source.installed = false
        assertThat(problem()).isEqualTo(PackProblem.NotInstalled)
        assertThat(loaded).isEmpty()
    }

    @Test
    fun aDifferentSignerIsRefusedBeforeAnythingIsLoaded() {
        source.packSigners = setOf("bb")
        assertThat(problem()).isEqualTo(PackProblem.SignatureMismatch)
        assertThat(loaded).isEmpty()
        source.packSigners = null
        assertThat(problem()).isEqualTo(PackProblem.SignatureMismatch)
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
    fun missingLibrariesOrAssetsHaveTheirOwnReasons() {
        File(libs, "libjni.so").delete()
        assertThat(problem()).isEqualTo(PackProblem.LibrariesMissing)
        assertThat(loaded).isEmpty()
        File(libs, "libjni.so").writeText("x")
        source.assets = FakeAssets(complete = false)
        assertThat(problem()).isEqualTo(PackProblem.AssetsMissing)
        source.libDir = null
        assertThat(problem()).isEqualTo(PackProblem.LibrariesMissing)
    }

    @Test
    fun anUnloadableLibraryIsReportedWithTheErrorClassOnly() {
        locator =
            SignedPackLocator(spec, source) { throw UnsatisfiedLinkError("/secret/path/lib.so") }
        val result = locator.locate() as PackResult.Unavailable
        assertThat(result.problem).isEqualTo(PackProblem.LibraryLoadFailed)
        assertThat(result.detail).isEqualTo("UnsatisfiedLinkError")
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
        locator = SignedPackLocator(spec, changing) { loaded += it }
        assertThat(problem()).isEqualTo(PackProblem.ChangedDuringLoad)
    }

    @Test
    fun theAvailableResultIsCachedPerInstallAndRefreshedAfterAnUpdate() {
        val first = locator.locate() as PackResult.Available
        val lookups = source.signerLookups
        val assetLookups = source.assetLookups
        val second = locator.locate() as PackResult.Available
        assertThat(second.pack).isSameInstanceAs(first.pack)
        assertThat(source.signerLookups).isEqualTo(lookups)
        assertThat(source.assetLookups).isEqualTo(assetLookups)

        // Updated: checked again (the new install is in the same directory, so it loads).
        source.stamp = PackStamp(2, 200)
        val third = locator.locate() as PackResult.Available
        assertThat(third.pack).isNotSameInstanceAs(first.pack)
        assertThat(source.signerLookups).isEqualTo(lookups + 1)
    }

    @Test
    fun aCachedPackThatWasUninstalledOrReSignedIsNotServed() {
        locator.locate()
        source.installed = false
        assertThat(problem()).isEqualTo(PackProblem.NotInstalled)
        source.installed = true
        source.stamp = PackStamp(3, 300)
        source.packSigners = setOf("evil")
        assertThat(problem()).isEqualTo(PackProblem.SignatureMismatch)
    }

    private fun reinstall(content: String): File {
        val dir = tmp.newFolder("lib-${source.stamp.lastUpdateTime + 1}")
        spec.libraries.forEach { File(dir, it).writeText(content) }
        source.libDir = dir.path
        source.stamp = PackStamp(source.stamp.versionCode + 1, source.stamp.lastUpdateTime + 1)
        return dir
    }

    @Test
    fun aReinstallWithIdenticalLibrariesInANewDirectoryIsAvailableWithoutReloading() {
        locator.locate()
        val before = loaded.size
        reinstall("x")
        assertThat(locator.loadedLibrariesStale()).isFalse()
        assertThat(locator.locate()).isInstanceOf(PackResult.Available::class.java)
        assertThat(loaded).hasSize(before)
    }

    @Test
    fun aReinstallWithDifferentLibrariesNeedsARestart() {
        locator.locate()
        reinstall("y")
        assertThat(locator.loadedLibrariesStale()).isTrue()
        assertThat(problem()).isEqualTo(PackProblem.NeedsRestart)
        assertThat(loaded).hasSize(2) // the new ones are not loaded
    }

    @Test
    fun anUpdateWhoseLibrariesAreMissingIsLibrariesMissing() {
        locator.locate()
        val dir = reinstall("x")
        File(dir, "libbase.so").delete()
        assertThat(locator.loadedLibrariesStale()).isTrue()
        assertThat(problem()).isEqualTo(PackProblem.LibrariesMissing)
    }

    @Test
    fun aRemovedPackLeavesLoadedLibrariesStaleOnlyWhenSomethingWasLoaded() {
        assertThat(locator.loadedLibrariesStale()).isFalse()
        locator.locate()
        assertThat(locator.loadedLibrariesStale()).isFalse()
        source.installed = false
        assertThat(locator.loadedLibrariesStale()).isTrue()
    }

    @Test
    fun twoSpecsTrackTheirLoadedLibrariesIndependently() {
        val otherSpec = PackSpec("other.pack", listOf("libother.so"), listOf("model.bin"))
        val otherLibs = tmp.newFolder("other-lib")
        File(otherLibs, "libother.so").writeText("z")
        val otherSource = FakeSource(otherLibs.path)
        val otherLoaded = mutableListOf<String>()
        val other = SignedPackLocator(otherSpec, otherSource) { otherLoaded += it }

        locator.locate()
        assertThat(other.loadedLibrariesStale()).isFalse() // nothing loaded for the other spec yet
        assertThat(other.locate()).isInstanceOf(PackResult.Available::class.java)
        assertThat(otherLoaded.map { File(it).name }).containsExactly("libother.so")
        assertThat(loaded).hasSize(2)

        // The first pack comes back with different libraries; the other one is untouched.
        reinstall("y")
        assertThat(locator.loadedLibrariesStale()).isTrue()
        assertThat(problem()).isEqualTo(PackProblem.NeedsRestart)
        assertThat(other.loadedLibrariesStale()).isFalse()
        assertThat(other.locate()).isInstanceOf(PackResult.Available::class.java)

        // And the other way round: the other pack is removed, the first one is unaffected.
        locator = SignedPackLocator(spec, FakeSource(libs.path)) { loaded += it }
        otherSource.installed = false
        assertThat(other.loadedLibrariesStale()).isTrue()
        assertThat(locator.loadedLibrariesStale()).isFalse()
    }

    @Test
    fun theNeuralSpecNamesTheNeuralPack() {
        assertThat(NEURAL_PACK_SPEC.packageName).isEqualTo("dev.keycorrect.neural")
        assertThat(NEURAL_PACK_SPEC.libraries).containsExactly("libkcllama.so")
        assertThat(NEURAL_PACK_SPEC.assets).containsExactly("models/default.gguf")
    }
}

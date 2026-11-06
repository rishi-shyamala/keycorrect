package dev.keycorrect.core.packs

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * An optional pack: a separate APK with native libraries and uncompressed assets.
 *
 * @property packageName the pack's application id; the keyboard needs a matching `<queries><package
 *   android:name="..."/></queries>` entry to see it.
 * @property libraries the native library file names, in load order (a library goes after the ones
 *   it needs).
 * @property assets the asset paths that must exist, stored uncompressed (readable in place).
 */
data class PackSpec(
    val packageName: String,
    val libraries: List<String>,
    val assets: List<String>,
)

/** Why a pack can't be used. */
enum class PackProblem {
    /** The pack isn't installed (or the keyboard can't see it: see `<queries>`). */
    NotInstalled,

    /** The pack is signed with a different key than the keyboard, so nothing from it is loaded. */
    SignatureMismatch,

    /**
     * The pack is installed but its native libraries aren't on disk (wrong ABI, damaged install).
     */
    LibrariesMissing,

    /**
     * A native library is there but couldn't be loaded (the result's detail is the error class).
     */
    LibraryLoadFailed,

    /** The pack's asset files are missing or compressed (not readable in place). */
    AssetsMissing,

    /** The pack was updated or replaced while it was being checked; the next try checks again. */
    ChangedDuringLoad,

    /**
     * The pack was updated or reinstalled with different native libraries after this process loaded
     * the old ones; a library can't be unloaded, so the keyboard's process has to restart.
     */
    NeedsRestart,
}

/** An installed, verified pack whose native libraries are loaded. */
class SignedPack internal constructor(private val source: PackAssets, val versionName: String?) {
    /** The pack's assets, read in place (nothing is copied). */
    val assets: AssetManager
        get() = source.manager

    /** Opens an asset of the pack for reading in place; it must be stored uncompressed. */
    @Throws(IOException::class)
    fun openAssetFd(path: String): AssetFileDescriptor = source.manager.openFd(path)
}

/** The outcome of [SignedPackLocator.locate]. */
sealed interface PackResult {
    data class Available(val pack: SignedPack) : PackResult

    /** [detail] is the class name of the error behind [PackProblem.LibraryLoadFailed]. */
    data class Unavailable(val problem: PackProblem, val detail: String? = null) : PackResult
}

/** Loads a native library by absolute path (a seam for tests). */
internal fun interface NativeLoader {
    fun load(path: String)
}

/** What identifies one install of the pack: it changes with every update. */
internal data class PackStamp(val versionCode: Long, val lastUpdateTime: Long)

/** What the package manager says about a package; [signers] only when asked for. */
internal class PackFacts(
    val stamp: PackStamp,
    val versionName: String?,
    val signers: Set<String>?,
    val nativeLibraryDir: String?,
)

/** The pack's assets; [manager] is only touched by the engine (tests never read it). */
internal interface PackAssets {
    val manager: AssetManager

    /** Whether every asset of the spec is there and stored uncompressed (readable in place). */
    fun hasAssets(): Boolean
}

/** The Android lookups behind [SignedPackLocator] (a seam for tests). */
internal interface PackSource {
    /**
     * The pack's facts, or null when it isn't installed. Signers are costly: ask only when needed.
     */
    fun pack(withSigners: Boolean): PackFacts?

    /** The keyboard's own signer digests (empty when unreadable). */
    fun ownSigners(): Set<String>

    /** The pack's assets, or null when it isn't installed. */
    fun assets(): PackAssets?
}

private class AndroidPackSource(private val context: Context, private val spec: PackSpec) :
    PackSource {
    override fun pack(withSigners: Boolean): PackFacts? =
        info(spec.packageName, withSigners)?.let {
            PackFacts(
                PackStamp(it.longVersionCode, it.lastUpdateTime),
                it.versionName,
                if (withSigners) signers(it) else null,
                it.applicationInfo?.nativeLibraryDir,
            )
        }

    override fun ownSigners(): Set<String> =
        info(context.packageName, withSigners = true)?.let(::signers).orEmpty()

    override fun assets(): PackAssets? =
        try {
            val assets = context.createPackageContext(spec.packageName, 0).assets
            object : PackAssets {
                override val manager = assets

                override fun hasAssets() =
                    spec.assets.all { path ->
                        try {
                            assets.openFd(path).use { true }
                        } catch (_: IOException) {
                            false
                        }
                    }
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun info(name: String, withSigners: Boolean): PackageInfo? =
        try {
            context.packageManager.getPackageInfo(
                name,
                if (withSigners) PackageManager.GET_SIGNING_CERTIFICATES else 0,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    /** The signer digests; for a rotated key, the current signers only (what we vouch for). */
    private fun signers(info: PackageInfo): Set<String>? =
        info.signingInfo?.apkContentsSigners?.mapTo(HashSet()) { certDigest(it.toByteArray()) }
}

/**
 * Finds an optional pack (a separate APK named by the [PackSpec]), checks that it is signed by the
 * keyboard's own key, and loads its native libraries from its install directory.
 *
 * The pack's assets are taken first, then the signature is checked, the libraries are loaded, and
 * the pack's version and update time are read again: a pack replaced meanwhile is refused. An
 * [Available][PackResult.Available] result is kept per install (version code and update time), so
 * later calls only look the stamp up.
 *
 * Libraries are loaded once per process and can't be unloaded. A reinstall or update puts the pack
 * in a new directory, so the SHA-256 of each loaded library is recorded: a new install whose
 * libraries are identical is used as it is (its assets are read from its own package), a different
 * one gives [PackProblem.NeedsRestart] until the keyboard's process restarts. What is loaded is
 * tracked per package name, so several packs can share a process.
 *
 * **Threading:** [locate] and [loadedLibrariesStale] read files (hashing the libraries the first
 * time and after an update) and call the package manager, so they must never run on the main
 * thread.
 */
class SignedPackLocator
internal constructor(
    private val spec: PackSpec,
    private val source: PackSource,
    private val loader: NativeLoader,
) {
    constructor(
        context: Context,
        spec: PackSpec,
    ) : this(
        spec,
        AndroidPackSource(context.applicationContext, spec),
        NativeLoader {
            System.load(it)
        },
    )

    private var cached: Pair<PackStamp, SignedPack>? = null

    @Synchronized
    fun locate(): PackResult =
        try {
            PackResult.Available(cachedPack() ?: open())
        } catch (e: PackException) {
            cached = null
            PackResult.Unavailable(e.problem, e.detail)
        }

    /**
     * Whether libraries are loaded in this process that the installed pack no longer provides (the
     * pack was removed, or replaced with different libraries): the process should restart when it
     * is idle. False when nothing is loaded or the installed libraries are the loaded ones.
     */
    @Synchronized
    fun loadedLibrariesStale(): Boolean =
        synchronized(lock) {
            val loaded = loadedByPackage[spec.packageName] ?: return false
            val libDir = source.pack(withSigners = false)?.nativeLibraryDir ?: return true
            if (libDir == loaded.dir) return false
            val files = spec.libraries.map { File(libDir, it) }
            if (!files.all { it.isFile }) return true
            return try {
                hashes(files) != loaded.hashes
            } catch (_: IOException) {
                true
            }
        }

    private class PackException(val problem: PackProblem, val detail: String? = null) :
        Exception(problem.name)

    private fun fail(problem: PackProblem, detail: String? = null): Nothing =
        throw PackException(problem, detail)

    private fun cachedPack(): SignedPack? {
        val (stamp, pack) = cached ?: return null
        val now = source.pack(withSigners = false) ?: fail(PackProblem.NotInstalled)
        return if (now.stamp == stamp) pack else null
    }

    private fun open(): SignedPack {
        val before = source.pack(withSigners = true) ?: fail(PackProblem.NotInstalled)
        // The assets before the signature check, so both refer to the install that was checked
        // (the stamp is compared again at the end).
        val assets = source.assets() ?: fail(PackProblem.NotInstalled)
        checkPackSigners(before.signers, source.ownSigners())?.let { fail(it) }
        loadLibraries(before.nativeLibraryDir ?: fail(PackProblem.LibrariesMissing))
        if (!assets.hasAssets()) fail(PackProblem.AssetsMissing)
        val after = source.pack(withSigners = false) ?: fail(PackProblem.NotInstalled)
        if (after.stamp != before.stamp) fail(PackProblem.ChangedDuringLoad)
        return SignedPack(assets, before.versionName).also { cached = before.stamp to it }
    }

    private fun loadLibraries(libDir: String) {
        synchronized(lock) {
            val files = spec.libraries.map { File(libDir, it) }
            val from = loadedByPackage[spec.packageName]
            if (from?.dir == libDir) return
            if (!files.all { it.isFile }) fail(PackProblem.LibrariesMissing)
            val digests =
                try {
                    hashes(files)
                } catch (_: IOException) {
                    fail(PackProblem.LibrariesMissing)
                }
            if (from != null) {
                // A new install path: the loaded copy can only be used if the files are the same.
                if (digests == from.hashes) return
                fail(PackProblem.NeedsRestart)
            }
            try {
                // In the spec's order: a library goes after the ones it needs.
                files.forEach { loader.load(it.absolutePath) }
            } catch (e: UnsatisfiedLinkError) {
                fail(PackProblem.LibraryLoadFailed, e.javaClass.simpleName)
            }
            loadedByPackage[spec.packageName] = LoadedLibraries(libDir, digests)
        }
    }

    /** The libraries of one pack that this process has loaded: where from, and what they hold. */
    private class LoadedLibraries(val dir: String, val hashes: List<String>)

    internal companion object {
        private val lock = Any()

        /** What each pack (by package name) has loaded into this process; guarded by [lock]. */
        private val loadedByPackage = HashMap<String, LoadedLibraries>()

        /** Forgets the loaded libraries, for tests that load through a fake loader. */
        internal fun resetLoadedForTest() = synchronized(lock) { loadedByPackage.clear() }

        private fun hashes(files: List<File>): List<String> = files.map(::sha256)

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(HASH_BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        }

        private const val HASH_BUFFER = 64 * 1024
    }
}

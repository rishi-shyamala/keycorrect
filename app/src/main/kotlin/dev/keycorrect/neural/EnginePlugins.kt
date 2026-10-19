package dev.keycorrect.neural

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.keycorrect.core.packs.NeuralPackLayout
import dev.keycorrect.core.packs.certDigest
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.engine.EngineContract
import java.security.MessageDigest
import java.util.Locale

/** What the package manager says about one plugin service (a seam for tests). */
internal data class PluginFacts(
    /** The service's [ComponentName.flattenToString]. */
    val component: String,
    val packageName: String,
    /** The label to show (the service's, or else the app's). */
    val label: String,
    /** SHA-256 digests (hex) of the package's current signing certificates; empty = unreadable. */
    val certDigests: Set<String>,
    /** Whether the package asks for android.permission.INTERNET. */
    val requestsInternet: Boolean,
)

/** Where [EnginePlugins] gets its facts from. */
internal interface PluginSource {
    /** Every installed service that answers [EngineContract.ACTION]. */
    fun all(): List<PluginFacts>

    /** The facts of [component] if it is such a service, else null. */
    fun find(component: String): PluginFacts?
}

/** A discovered plugin with its approval state. */
internal data class EnginePlugin(
    val component: String,
    val packageName: String,
    val label: String,
    /** The pinned certificate (see [EngineApprovals.pin]); null when it can't be read. */
    val cert: String?,
    val requestsInternet: Boolean,
    /** Approved for exactly this certificate. */
    val approved: Boolean,
)

/**
 * The approval rules for plugin engines. An approval pins the plugin's component and the SHA-256 of
 * its signing certificate (an entry `<component>|<cert>` of `KeyboardSettings.approvedEngines`); a
 * plugin whose certificate changed, or that can't be read, is not approved.
 */
internal object EngineApprovals {
    private const val SEPARATOR = '|'
    private const val GROUP = 4
    private const val GROUPS = 4

    /** One value for a plugin's current signers: their digest, or a digest of the sorted digests. */
    fun pin(certDigests: Set<String>): String? =
        when (certDigests.size) {
            0 -> null
            1 -> certDigests.first()
            else -> sha256Hex(certDigests.sorted().joinToString(","))
        }

    fun entry(component: String, pin: String): String = "$component$SEPARATOR$pin"

    fun isApproved(approved: Set<String>, component: String, pin: String?): Boolean =
        pin != null && entry(component, pin) in approved

    /** [approved] with [component] pinned to [pin], replacing any older pin of it. */
    fun approve(approved: Set<String>, component: String, pin: String): Set<String> =
        revoke(approved, component) + entry(component, pin)

    fun revoke(approved: Set<String>, component: String): Set<String> =
        approved.filterNot { it.startsWith("$component$SEPARATOR") }.toSet()

    /** A certificate pin for display: the first 16 hex digits in groups of four. */
    fun shortCert(pin: String?): String =
        pin?.take(GROUP * GROUPS)?.chunked(GROUP)?.joinToString(" ").orEmpty()

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
            "%02x".format(Locale.ROOT, it)
        }
}

/** Finds the installed plugin engines and says which of them the user has approved. */
internal class EnginePlugins(private val source: PluginSource) {
    /** The installed plugins, sorted by label, with their state against [approved]. */
    fun list(approved: Set<String>): List<EnginePlugin> =
        source
            .all()
            .map { toPlugin(it, approved) }
            .sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.component }))

    /** Whether [component] is installed as a plugin and approved for its current certificate. */
    fun isApproved(component: String, approved: Set<String>): Boolean =
        find(component, approved)?.approved == true

    /** The current state of [component], or null when it isn't an installed plugin. */
    fun find(component: String, approved: Set<String>): EnginePlugin? =
        source.find(component)?.let { toPlugin(it, approved) }

    private fun toPlugin(facts: PluginFacts, approved: Set<String>): EnginePlugin {
        val pin = EngineApprovals.pin(facts.certDigests)
        return EnginePlugin(
            facts.component,
            facts.packageName,
            facts.label,
            pin,
            facts.requestsInternet,
            EngineApprovals.isApproved(approved, facts.component, pin),
        )
    }
}

/** [PluginSource] over the package manager. Needs the `<queries>` entry for the action. */
internal class AndroidPluginSource(context: Context) : PluginSource {
    private val pm: PackageManager = context.packageManager
    private val ownPackage = context.packageName

    override fun all(): List<PluginFacts> =
        resolve(Intent(EngineContract.ACTION)).mapNotNull { info ->
            val service = info.serviceInfo ?: return@mapNotNull null
            if (service.packageName == ownPackage) return@mapNotNull null
            factsOf(service.packageName, service.name, info.loadLabel(pm).toString())
        }

    override fun find(component: String): PluginFacts? {
        val name = ComponentName.unflattenFromString(component) ?: return null
        val info =
            resolve(Intent(EngineContract.ACTION).setComponent(name)).firstOrNull() ?: return null
        val service = info.serviceInfo ?: return null
        if (service.packageName == ownPackage) return null
        return factsOf(service.packageName, service.name, info.loadLabel(pm).toString())
    }

    private fun factsOf(packageName: String, className: String, label: String): PluginFacts? {
        val info = packageInfo(packageName) ?: return null
        val digests =
            info.signingInfo?.apkContentsSigners?.mapTo(HashSet()) { certDigest(it.toByteArray()) }
        return PluginFacts(
            component = ComponentName(packageName, className).flattenToString(),
            packageName = packageName,
            label = label.ifBlank { packageName },
            certDigests = digests.orEmpty(),
            requestsInternet =
                info.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true,
        )
    }

    private fun resolve(intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentServices(intent, 0)
        }

    private fun packageInfo(packageName: String) =
        try {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(packageName, flags)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
}

/** Which engine the keyboard talks to right now. */
internal sealed interface EngineTarget {
    /** None: the model is off, or there is nothing to run. */
    data object None : EngineTarget

    data object Builtin : EngineTarget

    data class Plugin(val component: String) : EngineTarget
}

/**
 * The engine to bind for these settings. A plugin counts only while [pluginApproved] says it is
 * approved for its current certificate; otherwise the built-in engine is used (when
 * [builtinAvailable]: the neural pack is installed or an imported model is chosen).
 */
internal fun chooseEngine(
    neuralRerank: Boolean,
    lmStrength: Float,
    neuralEngine: String,
    builtinAvailable: () -> Boolean,
    pluginApproved: (String) -> Boolean,
): EngineTarget =
    when {
        !neuralRerank || lmStrength <= 0f -> EngineTarget.None
        neuralEngine != KeyboardSettings.ENGINE_BUILTIN && pluginApproved(neuralEngine) ->
            EngineTarget.Plugin(neuralEngine)
        builtinAvailable() -> EngineTarget.Builtin
        else -> EngineTarget.None
    }

/** Whether the neural pack is installed (the package exists; trust is checked by the engine). */
internal fun neuralPackInstalled(pm: PackageManager): Boolean =
    try {
        pm.getPackageInfo(NeuralPackLayout.PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

/** What the engine says about itself, for the settings screen. */
internal data class EngineInfo(val status: String?, val model: String?)

/** The language-model status line, before it is turned into words. */
internal sealed interface LmStatus {
    data class Ready(val model: String?) : LmStatus

    data object Loading : LmStatus

    data object NoModel : LmStatus

    /** The engine failed; [reason] is a short code from the engine, never user text. */
    data class Error(val reason: String) : LmStatus

    /** The engine isn't connected or doesn't answer. */
    data object NoAnswer : LmStatus
}

internal fun lmStatusOf(info: EngineInfo?): LmStatus {
    val status = info?.status
    return when {
        status == null -> LmStatus.NoAnswer
        status == EngineContract.STATUS_READY -> LmStatus.Ready(info.model?.takeIf { it.isNotBlank() })
        status == EngineContract.STATUS_LOADING -> LmStatus.Loading
        status == EngineContract.STATUS_NO_MODEL -> LmStatus.NoModel
        EngineContract.isError(status) ->
            LmStatus.Error(status.removePrefix(EngineContract.STATUS_ERROR_PREFIX))
        else -> LmStatus.NoAnswer
    }
}

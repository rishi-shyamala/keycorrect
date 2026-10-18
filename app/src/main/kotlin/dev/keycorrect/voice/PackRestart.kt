package dev.keycorrect.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PatternMatcher
import android.os.Process
import androidx.core.content.ContextCompat
import dev.keycorrect.feature.voicesherpa.VoicePackLayout
import kotlin.concurrent.thread

/**
 * Ends the keyboard's process (a seam for tests); the system starts the keyboard again when needed.
 */
fun interface Restarter {
    fun restart()
}

/** Kills this process. */
internal val killProcess = Restarter { Process.killProcess(Process.myPid()) }

/**
 * Native libraries can't be unloaded, so when the voice pack is removed or updated to different
 * libraries the keyboard's process has to restart before the new ones can load. This remembers that
 * ([pending]) and restarts only when the keyboard is hidden and no dictation is running, so typing
 * and dictation are never cut off; the system starts the keyboard again the next time it is shown.
 */
class PackRestartPolicy(
    private val restarter: Restarter,
    private val dictating: () -> Boolean,
) {
    @Volatile
    var pending = false
        private set

    /**
     * A package broadcast for the voice pack: [action] is the intent action, [replacing] whether a
     * removal is part of an update (the add follows). [stale] says whether the loaded libraries are
     * gone or differ from the installed ones; it may read files, so it is only asked for the
     * broadcasts that matter, and the caller runs this off the main thread.
     */
    fun onPackBroadcast(action: String?, replacing: Boolean, stale: () -> Boolean) {
        val relevant =
            when (action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REPLACED -> true
                Intent.ACTION_PACKAGE_REMOVED -> !replacing
                else -> false
            }
        if (relevant && stale()) pending = true
    }

    /** The tap on "the voice pack was updated": restart when the window next hides. */
    fun requestRestart() {
        pending = true
    }

    /**
     * The keyboard's window was hidden. Restarts the process when a restart is pending and nothing
     * is being dictated; returns whether it did.
     */
    fun onKeyboardHidden(): Boolean {
        if (!pending || dictating()) return false
        pending = false
        restarter.restart()
        return true
    }
}

/**
 * Registers a receiver for installs, updates and removals of the voice pack and feeds
 * [PackRestartPolicy]. The package must be visible to the keyboard (`<queries>` in the manifest).
 */
@Suppress("UnusedPrivateProperty") // false positive: used by the receiver below
class PackChangeWatcher(
    private val context: Context,
    private val policy: PackRestartPolicy,
    private val stale: () -> Boolean,
) {
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.data?.schemeSpecificPart != VoicePackLayout.PACKAGE) return
                val pendingResult = goAsync()
                // Reading and hashing the libraries must stay off the main thread.
                thread(isDaemon = true, name = "keycorrect-voicepack-watch") {
                    try {
                        policy.onPackBroadcast(
                            intent.action,
                            intent.getBooleanExtra(Intent.EXTRA_REPLACING, false),
                            stale,
                        )
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }

    fun register() {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
                addDataSchemeSpecificPart(VoicePackLayout.PACKAGE, PatternMatcher.PATTERN_LITERAL)
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }
}

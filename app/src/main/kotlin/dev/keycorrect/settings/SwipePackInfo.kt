@file:Suppress("InjectDispatcher") // one-off package and asset probes in a settings screen

package dev.keycorrect.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.keycorrect.core.packs.PackProblem
import dev.keycorrect.core.packs.PackResult
import dev.keycorrect.core.packs.SWIPE_PACK_SPEC
import dev.keycorrect.core.packs.SignedPackLocator
import dev.keycorrect.core.packs.SwipePackLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What Settings knows about the optional swipe pack (docs/PHASE10.md, 10a-4). */
sealed interface SwipePackState {
    /** Not looked at yet. */
    data object Checking : SwipePackState

    /** Installed and signed with the keyboard's key; [license] is its FUTO licence text. */
    data class Installed(val license: String?) : SwipePackState

    data object NotInstalled : SwipePackState

    /** Installed but the keyboard won't load it (another key, a missing file, a failed load). */
    data object Unusable : SwipePackState
}

/**
 * Asks the package manager and the pack itself; call it off the main thread. The pack's signature
 * is checked, so a pack signed by another key never counts and its licence text is never shown.
 * The licence is the pack's own `swipenet/LICENSE.md` asset.
 */
fun probeSwipePack(context: Context): SwipePackState =
    when (val result = SignedPackLocator(context.applicationContext, SWIPE_PACK_SPEC).locate()) {
        is PackResult.Available ->
            SwipePackState.Installed(
                runCatching {
                        result.pack.assets.open(SwipePackLayout.LICENSE).use {
                            it.readBytes().decodeToString()
                        }
                    }
                    .getOrNull()
            )
        is PackResult.Unavailable ->
            if (result.problem == PackProblem.NotInstalled) SwipePackState.NotInstalled
            else SwipePackState.Unusable
    }

/** The swipe pack's state for a screen; [SwipePackState.Checking] until the probe returns. */
@Composable
fun rememberSwipePackState(): SwipePackState {
    val context = LocalContext.current
    var state by remember { mutableStateOf<SwipePackState>(SwipePackState.Checking) }
    LaunchedEffect(Unit) { state = withContext(Dispatchers.Default) { probeSwipePack(context) } }
    return state
}

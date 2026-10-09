package dev.keycorrect.core.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * An [InputMethodService] that can host Jetpack Compose UI.
 *
 * ComposeView requires lifecycle, view-model-store and saved-state owners in its view tree, which
 * an IME window doesn't provide. This class supplies them and maps the IME's visibility onto the
 * lifecycle: RESUMED while the keyboard is shown, CREATED while hidden. Compose pauses its frame
 * clock below STARTED, so a hidden keyboard does no UI work.
 */
abstract class ComposeInputMethodService :
    InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    /** The keyboard UI. Called each time the system (re)creates the input view. */
    @Composable protected abstract fun KeyboardContent()

    override fun onCreate() {
        // Initialise owners before super.onCreate(): the framework may build the input view
        // during creation, and the saved-state registry must be restored before CREATED.
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        super.onCreate()
    }

    override fun onCreateInputView(): View {
        window.window?.decorView?.let(::installViewTreeOwners)
        return ComposeView(this).apply {
            installViewTreeOwners(this)
            setContent { KeyboardContent() }
        }
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    private fun installViewTreeOwners(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}

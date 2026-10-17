package dev.keycorrect.feature.keyboard

/**
 * Tells a restart of the input view caused by a configuration change (rotation, fold, font size)
 * from every other start. The keyboard keeps its layer and shift only for the former: a new field,
 * or the keyboard shown again after being hidden, always opens on the letters layer.
 *
 * `InputMethodService.onConfigurationChanged` rebuilds the views and restarts input in the same
 * call stack (`onStartInputView(restarting = true)`), possibly followed by one more
 * `onStartInputView` when the window is shown again. The platform's `restarting` flag alone cannot
 * be used, because apps also call `restartInput` for the same field when it regains focus. So the
 * service reports the change here and asks [isConfigRestart] for the starts that follow, which
 * count for [WINDOW_MS]. A change while the keyboard was not showing counts for nothing: the next
 * start is then a plain opening.
 */
class ConfigRestartTracker(private val clock: () -> Long) {
    private var changedAt = NEVER

    /** The configuration changed; [inputViewShown] is whether the keyboard was on screen. */
    fun onConfigurationChanged(inputViewShown: Boolean) {
        changedAt = if (inputViewShown) clock() else NEVER
    }

    /** Whether a start of the input view now is the aftermath of a configuration change. */
    fun isConfigRestart(): Boolean {
        if (changedAt == NEVER) return false
        val age = clock() - changedAt
        return age in 0..WINDOW_MS
    }

    /** The keyboard was hidden or the field changed: a later start is not a rotation's. */
    fun reset() {
        changedAt = NEVER
    }

    private companion object {
        const val WINDOW_MS = 1_500L
        const val NEVER = Long.MIN_VALUE
    }
}

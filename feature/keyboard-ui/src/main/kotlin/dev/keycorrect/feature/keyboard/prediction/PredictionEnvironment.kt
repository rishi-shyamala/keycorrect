package dev.keycorrect.feature.keyboard.prediction

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import dev.keycorrect.feature.keyboard.TimeoutHandle
import dev.keycorrect.feature.keyboard.TimeoutScheduler
import java.util.Date
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Everything prediction depends on, so the controller can be built with fakes in tests and with the
 * real thing by [create]: the [backend] (engine, shortcuts, user dictionary), the [loop] that runs
 * the engine off the main thread, the timer for the transient "add word" prompt, and the shortcut
 * variable resolver.
 */
class PredictionEnvironment
internal constructor(
    val backend: PredictionBackend,
    val loop: DecoderLoop,
    internal val scheduler: TimeoutScheduler,
    val expander: ShortcutExpander,
) {
    companion object {
        /** No prediction: keys are committed directly. Used before the app wires the engine. */
        val Disabled: PredictionEnvironment
            get() =
                PredictionEnvironment(
                    PredictionBackend.None,
                    DecoderLoop({ it.run() }, { it.run() }) { null },
                    { _, _ -> TimeoutHandle {} },
                    ShortcutExpander(),
                )

        /**
         * The production setup: a dedicated decoder thread (a daemon, at slightly raised priority
         * since it sits on the typing path), results and timers on the main looper, and
         * `{date}`/`{time}` formatted by the device's date and time settings.
         */
        fun create(context: Context, backend: PredictionBackend): PredictionEnvironment {
            val main = Handler(Looper.getMainLooper())
            val decoder: Executor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "keycorrect-decoder").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY + 1
                }
            }
            val app = context.applicationContext
            return PredictionEnvironment(
                backend,
                DecoderLoop(decoder, { main.post(it) }) { backend.predictor },
                { delayMs, action ->
                    main.postDelayed(action, delayMs)
                    TimeoutHandle { main.removeCallbacks(action) }
                },
                ShortcutExpander(
                    formatDate = { DateFormat.getMediumDateFormat(app).format(Date(it)) },
                    formatTime = { DateFormat.getTimeFormat(app).format(Date(it)) },
                ),
            )
        }
    }
}

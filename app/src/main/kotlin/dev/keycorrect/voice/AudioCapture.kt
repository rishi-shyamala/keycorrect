package dev.keycorrect.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import dev.keycorrect.feature.keyboard.voice.CaptureHandle
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Records the microphone as 16-bit mono PCM on a thread of its own, in 30 ms chunks, for the
 * recognizer ([start]). The microphone is open only between [start] and [CaptureHandle.stop]; the
 * audio is handed on and never kept or logged.
 */
class AudioCapture(private val context: Context) {
    /**
     * Starts recording; null when the microphone is not permitted or cannot be opened. [onAudio]
     * gets each chunk (the array is reused: copy to keep), [onLevel] its loudness from 0 to 1, and
     * [onFailure] is called once if a read fails or [onAudio] throws. All three run on the capture
     * thread.
     */
    @SuppressLint("MissingPermission") // checked just above
    @Suppress("ReturnCount") // each early return is one reason the microphone is not available
    fun start(
        sampleRate: Int,
        onAudio: (ShortArray, Int) -> Unit,
        onLevel: (Float) -> Unit,
        onFailure: () -> Unit,
    ): CaptureHandle? {
        if (
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val chunk = sampleRate * CHUNK_MS / MS_PER_SECOND
        val minBytes =
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (minBytes <= 0) return null
        val record =
            try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBytes, chunk * BYTES_PER_SAMPLE * BUFFER_CHUNKS),
                )
            } catch (_: SecurityException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return try {
            record.startRecording()
            val running = RecordingLoop(record, chunk, onAudio, onLevel, onFailure)
            Thread(running, "keycorrect-audio").apply { isDaemon = true }.start()
            CaptureHandle { running.stop() }
        } catch (_: IllegalStateException) {
            record.release()
            null
        }
    }

    private class RecordingLoop(
        private val record: AudioRecord,
        private val chunk: Int,
        private val onAudio: (ShortArray, Int) -> Unit,
        private val onLevel: (Float) -> Unit,
        private val onFailure: () -> Unit,
    ) : Runnable {
        @Volatile private var stopped = false

        fun stop() {
            stopped = true
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ShortArray(chunk)
            try {
                var read = if (stopped) 0 else record.read(buffer, 0, chunk)
                while (read > 0 && !stopped) {
                    onLevel(level(buffer, read))
                    onAudio(buffer, read)
                    read = record.read(buffer, 0, chunk)
                }
                if (read <= 0 && !stopped) onFailure()
            } catch (_: Exception) {
                // A consumer that throws must not leave the strip on "Listening": report it (the
                // message never holds audio or text).
                if (!stopped) onFailure()
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
    }

    companion object {
        private const val CHUNK_MS = 30
        private const val MS_PER_SECOND = 1000
        private const val BYTES_PER_SAMPLE = 2
        private const val BUFFER_CHUNKS = 8

        /** Speech sits well above this, a quiet room well below (dB relative to full scale). */
        private const val FLOOR_DB = -60f
        private const val FULL_SCALE = 32768f

        /** Loudness of the first [length] samples of [pcm], 0 (silence) to 1, on a dB scale. */
        fun level(pcm: ShortArray, length: Int): Float {
            var sum = 0.0
            for (i in 0 until length) {
                val v = pcm[i] / FULL_SCALE
                sum += v * v
            }
            val rms = if (length > 0) sqrt(sum / length).toFloat() else 0f
            val db = if (rms > 0f) 20f * log10(rms) else FLOOR_DB
            return ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
        }
    }
}

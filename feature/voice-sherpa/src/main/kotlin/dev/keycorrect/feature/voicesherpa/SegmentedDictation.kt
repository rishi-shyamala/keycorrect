package dev.keycorrect.feature.voicesherpa

import dev.keycorrect.core.voice.SttConfig
import dev.keycorrect.core.voice.SttError
import dev.keycorrect.core.voice.SttListener
import dev.keycorrect.core.voice.SttResult
import dev.keycorrect.core.voice.SttSession
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Voice-activity detection: turns a stream of audio into finished speech segments. The real one is
 * Silero VAD; tests use a fake. Used from the session's worker thread only.
 */
internal interface SpeechDetector {
    fun accept(samples: FloatArray)

    /** Closes the speech in progress, so [nextSegment] returns it too. */
    fun flush()

    /** Forgets the speech in progress and the segments waiting, as if starting afresh. */
    fun reset()

    /** The next finished speech segment, or null. */
    fun nextSegment(): FloatArray?

    /** True while a phrase is in progress: speech was heard and its closing pause isn't over. */
    val speechDetected: Boolean

    fun release()
}

/** Offline recognition of one speech segment (16 kHz mono floats in -1..1). */
internal interface SegmentRecognizer {
    fun recognize(segment: FloatArray): String

    fun release()
}

internal class SpeechComponents(val detector: SpeechDetector, val recognizer: SegmentRecognizer)

/**
 * One dictation: audio goes through the VAD, every finished speech segment is recognised by the
 * offline model, and the results are emitted as [SttResult.Partial] (the text of all finished
 * segments so far, stable, plus the words of the phrase in progress, not stable) and, after
 * [finish], one [SttResult.Final].
 *
 * Live words: the session keeps its own copy of the speech in progress (from the VAD's speech
 * start) and, while speech goes on, decodes it again about every [LiveTuning.INTERVAL_MS] on the
 * worker, so the words appear while speaking and may change until the pause, when the finished
 * segment is decoded once more and committed. A live decode never queues up behind another: the
 * next one waits at least twice the time the last took (within [LiveTuning.MIN_INTERVAL_MS] to
 * [LiveTuning.MAX_INTERVAL_MS]). A [SttResult.Progress] goes out about every 200 ms of audio.
 *
 * A segment that decodes to nothing (or hardly anything) for its length is split at its quietest
 * point and the halves are decoded, so a long phrase is not lost; when the whole session heard
 * speech but produced no text it ends with [SttError.Unclear].
 *
 * Everything, including loading the models ([create]), happens in order on one worker thread, so
 * [acceptAudio] never blocks and the calling (audio) thread is not held up by decoding. Models are
 * freed when the session ends. Results go to [listener] from the worker thread; once [cancel] was
 * called nothing is emitted any more. No text or audio is ever logged.
 */
@Suppress("TooManyFunctions") // one small state machine
internal class SegmentedDictation(
    private val listener: SttListener,
    private val create: () -> SpeechComponents,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "keycorrect-stt")
    },
    /** The longest phrase the VAD lets through, reported in [SttResult.Progress]. */
    private val phraseLimitMillis: Long = SherpaTuning.DEFAULT_PHRASE_MILLIS,
    /** Live words: [SttConfig.LIVE_AUTO], [SttConfig.LIVE_OFF] or a fixed interval. */
    private val liveUpdateMillis: Long = SttConfig.LIVE_AUTO,
    /** Milliseconds from any monotonic clock; replaced in tests. */
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) : SttSession {
    // Worker-thread state.
    private var components: SpeechComponents? = null
    private val text = StringBuilder()
    private var failed = false

    // The phrase in progress: a pre-roll while idle, the phrase's audio once speech starts.
    private val phrase = SampleBuffer()
    private var collecting = false
    private var liveText = ""
    private var lastEmitted = ""
    private val schedule = LiveSchedule(liveUpdateMillis)
    private var progressSamples = 0

    private val limitSamples =
        (phraseLimitMillis * SherpaTuning.SAMPLE_RATE / MILLIS_PER_SECOND).toInt()

    // The copy of the phrase never grows past the limit plus a margin.
    private val capSamples = limitSamples + SherpaTuning.SAMPLE_RATE

    // Seconds of speech the VAD handed over, for telling "unclear" from "silence".
    private var speechSamples = 0L

    private val pending = AtomicInteger()

    @Volatile private var cancelled = false
    @Volatile private var finished = false

    init {
        post { components = create() }
    }

    override fun acceptAudio(pcm: ShortArray, length: Int) {
        if (cancelled || finished || length <= 0) return
        val samples = FloatArray(length) { pcm[it] / PCM_SCALE }
        post {
            val c = components ?: return@post
            c.detector.accept(samples)
            track(c, samples)
            drain(c)
            if (collecting && phrase.size >= limitSamples) cutPhrase(c)
            if (collecting && liveUpdateMillis != SttConfig.LIVE_OFF) liveDecode(c)
            reportProgress(c, samples.size)
        }
    }

    override val busy: Boolean
        get() = !cancelled && pending.get() > 0

    @Synchronized
    override fun finish() {
        if (cancelled || finished) return
        finished = true
        post {
            components?.let {
                it.detector.flush()
                drain(it)
            }
            emit(
                when {
                    text.isNotEmpty() -> SttResult.Final(text.toString())
                    speechSamples >= LiveTuning.UNCLEAR_MIN_SAMPLES ->
                        SttResult.Error(SttError.Unclear)
                    else -> SttResult.Error(SttError.NoMatch)
                }
            )
        }
        // Queued tasks still run, then the worker frees the models and stops.
        post(always = true) { close() }
        executor.shutdown()
    }

    @Synchronized
    override fun cancel() {
        if (cancelled) return
        cancelled = true
        // The task in progress completes (native calls can't be interrupted); queued ones skip.
        if (!finished) {
            post(always = true) { close() }
            executor.shutdown()
        }
    }

    /** Keeps [samples] in the phrase copy; before speech starts only a short pre-roll is kept. */
    private fun track(c: SpeechComponents, samples: FloatArray) {
        if (!collecting && c.detector.speechDetected) {
            collecting = true
            schedule.restart(clock())
        }
        if (collecting) phrase.appendUpTo(samples, capSamples)
        else phrase.appendKeepingLast(samples, LiveTuning.PRE_ROLL_SAMPLES)
    }

    private fun drain(c: SpeechComponents) {
        var ended = false
        while (!cancelled) {
            val segment = c.detector.nextSegment() ?: break
            ended = true
            commit(c.recognizer, segment)
        }
        if (!ended) return
        // The copy of the phrase is done with; the speech may go on (the VAD cut it), from here.
        phrase.clear()
        collecting = c.detector.speechDetected
        schedule.restart(clock())
    }

    /** Decodes a finished [segment] and adds its words to the stable text. */
    private fun commit(recognizer: SegmentRecognizer, segment: FloatArray) {
        speechSamples += segment.size
        val piece = recognizeSplitting(recognizer, segment).ifEmpty { liveText }
        liveText = ""
        if (piece.isEmpty()) return
        if (text.isNotEmpty()) text.append(' ')
        text.append(piece)
        lastEmitted = text.toString()
        emit(SttResult.Partial(lastEmitted, stablePrefixLength = text.length, endsSegment = true))
    }

    /**
     * The phrase reached the limit without a pause. Moonshine can't decode much more than this at
     * once and the VAD's own limit is only a hint (it waits for a quiet moment that may never
     * come), so the phrase is cut here: at the quietest point of its second half. The part before
     * is committed, the VAD starts over, and the rest is fed to it again as the new phrase.
     */
    private fun cutPhrase(c: SpeechComponents) {
        val all = phrase.copy()
        val cut = quietestPoint(all, LiveTuning.CUT_FROM, 1f, prefer = all.size)
        commit(c.recognizer, all.copyOfRange(0, cut))
        val rest = all.copyOfRange(cut, all.size)
        c.detector.reset()
        c.detector.accept(rest)
        phrase.clear()
        collecting = c.detector.speechDetected
        if (collecting) phrase.appendUpTo(rest, capSamples)
        else phrase.appendKeepingLast(rest, LiveTuning.PRE_ROLL_SAMPLES)
        schedule.restart(clock())
    }

    /** Decodes the phrase so far when it is time and returns; never waits for a slot. */
    private fun liveDecode(c: SpeechComponents) {
        val size = phrase.size
        val now = clock()
        if (cancelled || size > capSamples || !schedule.due(size, now)) return
        val heard = runCatching { c.recognizer.recognize(phrase.copy()).trim() }.getOrDefault("")
        schedule.decoded(size, now, clock())
        if (heard.isEmpty() || !plausible(heard, size)) return
        liveText = heard
        showLive(heard)
    }

    private fun showLive(heard: String) {
        val shown = if (text.isEmpty()) heard else "$text $heard"
        if (shown == lastEmitted) return
        lastEmitted = shown
        emit(SttResult.Partial(shown, stablePrefixLength = text.length))
    }

    private fun reportProgress(c: SpeechComponents, added: Int) {
        progressSamples += added
        if (progressSamples < LiveTuning.PROGRESS_SAMPLES) return
        progressSamples = 0
        val millis =
            if (collecting) phrase.size * MILLIS_PER_SECOND / SherpaTuning.SAMPLE_RATE else 0L
        emit(SttResult.Progress(millis, phraseLimitMillis, c.detector.speechDetected))
    }

    private fun emit(result: SttResult) {
        if (!cancelled) listener.onResult(result)
    }

    private fun close() {
        components?.let {
            runCatching { it.detector.release() }
            runCatching { it.recognizer.release() }
        }
        components = null
    }

    /**
     * Runs [block] on the worker, unless the session was cancelled or failed ([always] tasks run
     * regardless: the clean-up). A failure ends the session with [SttError.Engine].
     */
    private fun post(always: Boolean = false, block: () -> Unit) {
        pending.incrementAndGet()
        try {
            executor.execute {
                try {
                    if (!always && (cancelled || failed)) return@execute
                    // Deliberately neither logged nor wrapped: a message could hold user text.
                    runCatching(block).onFailure {
                        failed = true
                        emit(SttResult.Error(SttError.Engine))
                        close()
                    }
                } finally {
                    pending.decrementAndGet()
                }
            }
        } catch (_: RejectedExecutionException) {
            // Cancelled or finished already.
            pending.decrementAndGet()
        }
    }

    private companion object {
        const val PCM_SCALE = 32768f
        const val NANOS_PER_MILLI = 1_000_000L
        const val MILLIS_PER_SECOND = 1000L
    }
}

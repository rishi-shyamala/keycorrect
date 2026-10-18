package dev.keycorrect.feature.voicesherpa

import kotlin.math.abs

/** Timing of the live words and of the split-and-retry (docs/PHASE6.md, "Long dictation"). */
internal object LiveTuning {
    private const val RATE = SherpaTuning.SAMPLE_RATE

    /** The first live decode comes this long after speech starts; later ones adapt. */
    const val INTERVAL_MS = 700L

    /** The gap to the next live decode is twice the last one's duration, within these. */
    const val MIN_INTERVAL_MS = 500L
    const val MAX_INTERVAL_MS = 1500L

    /** Audio kept from before the VAD noticed speech, so the first word isn't clipped. */
    const val PRE_ROLL_SAMPLES = RATE * 3 / 10

    /** No live decode of less than this, or with less than [MIN_NEW_SAMPLES] new since the last. */
    const val MIN_SAMPLES = RATE / 2
    const val MIN_NEW_SAMPLES = RATE / 5

    /** A progress report per this much audio (200 ms: five a second). */
    const val PROGRESS_SAMPLES = RATE / 5

    /** A fixed interval is raised to twice the decode time, but not beyond this. */
    const val MAX_RAISED_MS = 3000L

    /** Speech of at least this much with no text at all is "unclear", not "nothing heard". */
    const val UNCLEAR_MIN_SAMPLES = RATE.toLong()

    /** A segment longer than this that decodes to (nearly) nothing is split and retried. */
    const val SPLIT_MIN_SAMPLES = RATE * 4

    /** Fewer than one word per this many seconds counts as "nearly nothing". */
    const val SECONDS_PER_WORD = 2.5f

    /** More words per second than anyone speaks: a decoder loop, not speech. */
    const val MAX_WORDS_PER_SECOND = 6f
    const val MAX_WORDS_SLACK = 3

    /** A phrase that hits the limit is cut in its second half (from this fraction on). */
    const val CUT_FROM = 0.5f

    /** The quiet point is looked for in frames of 30 ms. */
    const val FRAME_SAMPLES = RATE * 30 / 1000
}

/**
 * When the next live decode is due. [fixedMillis] is the user's interval, or <= 0 for Auto: twice
 * the time the last decode took, within 0.5 to 1.5 s. A fixed interval is raised to twice the
 * decode time when the phone can't keep up (up to [LiveTuning.MAX_RAISED_MS]). Worker thread only.
 */
internal class LiveSchedule(private val fixedMillis: Long) {
    private var intervalMs = if (fixedMillis > 0) fixedMillis else LiveTuning.INTERVAL_MS
    private var at = 0L
    private var decodedSamples = 0

    /** A new phrase (or its remainder) starts at [now]. */
    fun restart(now: Long) {
        at = now
        decodedSamples = 0
    }

    /** Whether a phrase of [size] samples should be decoded at [now]. */
    fun due(size: Int, now: Long): Boolean =
        size >= LiveTuning.MIN_SAMPLES &&
            size - decodedSamples >= LiveTuning.MIN_NEW_SAMPLES &&
            now - at >= intervalMs

    /** A decode of [size] samples ran from [start] to [end]. */
    fun decoded(size: Int, start: Long, end: Long) {
        val twice = (end - start) * 2
        intervalMs =
            if (fixedMillis > 0) maxOf(fixedMillis, minOf(twice, LiveTuning.MAX_RAISED_MS))
            else twice.coerceIn(LiveTuning.MIN_INTERVAL_MS, LiveTuning.MAX_INTERVAL_MS)
        at = end
        decodedSamples = size
    }
}

/** A growable float buffer for the phrase in progress. Worker thread only. */
internal class SampleBuffer {
    private var data = FloatArray(INITIAL)
    var size = 0
        private set

    fun appendUpTo(src: FloatArray, cap: Int) {
        val n = minOf(src.size, cap - size)
        if (n > 0) put(src, 0, n)
    }

    /** Appends [src] and drops the oldest samples beyond the last [keep]. */
    fun appendKeepingLast(src: FloatArray, keep: Int) {
        if (src.size >= keep) {
            size = 0
            put(src, src.size - keep, keep)
            return
        }
        val drop = size + src.size - keep
        if (drop > 0) {
            System.arraycopy(data, drop, data, 0, size - drop)
            size -= drop
        }
        put(src, 0, src.size)
    }

    fun copy(): FloatArray = data.copyOf(size)

    /** Empties the buffer and gives a grown array back. */
    fun clear() {
        size = 0
        if (data.size > SHRINK_ABOVE) data = FloatArray(INITIAL)
    }

    private fun put(src: FloatArray, from: Int, n: Int) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
        System.arraycopy(src, from, data, size, n)
        size += n
    }

    private companion object {
        const val INITIAL = 16_000
        const val SHRINK_ABOVE = 64_000
    }
}

internal fun wordCount(text: String) = text.split(' ').count { it.isNotEmpty() }

/** Whether [text] could be speech of [samples] length (not a decoder stuck repeating itself). */
internal fun plausible(text: String, samples: Int): Boolean {
    val seconds = samples.toFloat() / SherpaTuning.SAMPLE_RATE
    return wordCount(text) <= seconds * LiveTuning.MAX_WORDS_PER_SECOND + LiveTuning.MAX_WORDS_SLACK
}

/**
 * Decodes [segment]. Moonshine fails outright on long audio (docs/PHASE6.md) and a decode may come
 * back empty or with a few words for a long stretch of speech; then the segment is cut at its
 * quietest point near the middle and the halves are decoded (again cut when still long), and the
 * result with more words wins. Speech is never dropped silently.
 */
internal fun recognizeSplitting(recognizer: SegmentRecognizer, segment: FloatArray): String {
    val text = recognizer.recognize(segment).trim()
    if (segment.size <= LiveTuning.SPLIT_MIN_SAMPLES || !nearlyEmpty(text, segment.size))
        return text
    val cut = quietestPoint(segment, 0.25f, 0.75f, prefer = segment.size / 2)
    val left = recognizeSplitting(recognizer, segment.copyOfRange(0, cut))
    val right = recognizeSplitting(recognizer, segment.copyOfRange(cut, segment.size))
    val joined = listOf(left, right).filter { it.isNotEmpty() }.joinToString(" ")
    return if (wordCount(joined) > wordCount(text)) joined else text
}

private fun nearlyEmpty(text: String, samples: Int): Boolean {
    val seconds = samples.toFloat() / SherpaTuning.SAMPLE_RATE
    return wordCount(text) * LiveTuning.SECONDS_PER_WORD < seconds
}

/**
 * The sample index of the quietest 30 ms frame (lowest RMS) between the fractions [from] and [to]
 * of [samples]; the one nearest [prefer] wins a tie. Always inside the array, away from its ends.
 */
internal fun quietestPoint(samples: FloatArray, from: Float, to: Float, prefer: Int): Int {
    val frame = LiveTuning.FRAME_SAMPLES
    val first = (samples.size * from).toInt()
    val last = minOf(samples.size - frame, (samples.size * to).toInt() - frame)
    var best = prefer
    var bestEnergy = Float.MAX_VALUE
    var start = first
    while (start <= last) {
        var energy = 0f
        for (i in start until start + frame) energy += samples[i] * samples[i]
        val centre = start + frame / 2
        if (
            energy < bestEnergy ||
                (energy == bestEnergy && abs(centre - prefer) < abs(best - prefer))
        ) {
            bestEnergy = energy
            best = centre
        }
        start += frame
    }
    return best.coerceIn(1, samples.size - 1)
}

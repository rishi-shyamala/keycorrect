package dev.keycorrect.feature.voicesherpa

import kotlin.math.abs

/** Timing of the live words and of the split-and-retry (docs/PHASE6.md, "Long dictation"). */
internal object LiveTuning {
    private const val RATE = SherpaTuning.SAMPLE_RATE

    /** The first live decode comes this long after speech starts; later ones adapt. */
    const val INTERVAL_MS = 700L

    /** The gap to the next live decode is twice the last one's duration, within these. */
    const val MIN_INTERVAL_MS = 500L
    const val MAX_INTERVAL_MS = 3000L

    /**
     * Live decoding never takes more than this share of the worker's time over the last
     * [DUTY_WINDOW_MS], so the decodes that matter (a pause, Stop) are not held up by it.
     */
    const val MAX_DUTY = 0.4f
    const val DUTY_WINDOW_MS = 6000L

    /** Audio kept from before the VAD noticed speech, so the first word isn't clipped. */
    const val PRE_ROLL_SAMPLES = RATE * 3 / 10

    /** No live decode of less than this, or with less than [MIN_NEW_SAMPLES] new since the last. */
    const val MIN_SAMPLES = RATE / 2
    const val MIN_NEW_SAMPLES = RATE / 5

    /** A progress report per this much audio (200 ms: five a second). */
    const val PROGRESS_SAMPLES = RATE / 5

    /** A fixed interval is raised to twice the decode time, but not beyond this. */
    const val MAX_RAISED_MS = 3000L

    /**
     * Moonshine fails above about 9.25 s (docs/PHASE6.md); nothing longer than this is ever handed
     * to the recognizer: a longer stretch is split first.
     */
    const val MAX_DECODE_SAMPLES = RATE * 9

    /** Live words kept as a segment's text only if not longer than this many times plausible. */
    const val CHARS_PER_SECOND = 15f
    const val FALLBACK_FACTOR = 1.5f

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
 * the time the last decode took, within 0.5 to 3 s. A fixed interval is raised to twice the decode
 * time when the phone can't keep up (up to [LiveTuning.MAX_RAISED_MS]). Beyond that, no decode is
 * due while live decoding used more than [LiveTuning.MAX_DUTY] of the last
 * [LiveTuning.DUTY_WINDOW_MS]. Worker thread only.
 */
internal class LiveSchedule(private val fixedMillis: Long) {
    private var intervalMs = if (fixedMillis > 0) fixedMillis else LiveTuning.INTERVAL_MS
    private var at = 0L
    private var decodedSamples = 0
    private val recent = ArrayDeque<LongArray>() // [start, end] of the latest decodes

    /** A new phrase (or its remainder) starts at [now]. */
    fun restart(now: Long) {
        at = now
        decodedSamples = 0
    }

    /** Whether a phrase of [size] samples should be decoded at [now]. */
    fun due(size: Int, now: Long): Boolean =
        size >= LiveTuning.MIN_SAMPLES &&
            size - decodedSamples >= LiveTuning.MIN_NEW_SAMPLES &&
            now - at >= intervalMs &&
            busyMillis(now) <= LiveTuning.MAX_DUTY * LiveTuning.DUTY_WINDOW_MS

    /** Milliseconds of live decoding inside the window that ends at [now]. */
    private fun busyMillis(now: Long): Long {
        val from = now - LiveTuning.DUTY_WINDOW_MS
        while (recent.isNotEmpty() && recent.first()[1] <= from) recent.removeFirst()
        return recent.sumOf { minOf(it[1], now) - maxOf(it[0], from) }
    }

    /** A decode of [size] samples ran from [start] to [end]. */
    fun decoded(size: Int, start: Long, end: Long) {
        val twice = (end - start) * 2
        intervalMs =
            if (fixedMillis > 0) maxOf(fixedMillis, minOf(twice, LiveTuning.MAX_RAISED_MS))
            else twice.coerceIn(LiveTuning.MIN_INTERVAL_MS, LiveTuning.MAX_INTERVAL_MS)
        at = end
        decodedSamples = size
        recent.addLast(longArrayOf(start, end))
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

    /** Drops all but the last [keep] samples. */
    fun keepLast(keep: Int) {
        if (size <= keep) return
        System.arraycopy(data, size - keep, data, 0, keep)
        size = keep
    }

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
 * Whether live words [text] can be taken as the text of a finished segment of [samples] length: not
 * longer than [LiveTuning.FALLBACK_FACTOR] times the characters speech of that duration has (the
 * live copy may hold more than the segment, e.g. after a forced cut).
 */
internal fun fitsSegment(text: String, samples: Int): Boolean {
    val seconds = maxOf(samples.toFloat() / SherpaTuning.SAMPLE_RATE, 1f)
    return text.length <= seconds * LiveTuning.CHARS_PER_SECOND * LiveTuning.FALLBACK_FACTOR
}

/**
 * Decodes [segment]. Moonshine fails outright on long audio (docs/PHASE6.md) and a decode may come
 * back empty or with a few words for a long stretch of speech; then the segment is cut at its
 * quietest point near the middle and the halves are decoded (again cut when still long), and the
 * result with more words wins. Speech is never dropped silently.
 */
internal fun recognizeSplitting(recognizer: SegmentRecognizer, segment: FloatArray): String {
    val text = recognizeBounded(recognizer, segment)
    if (segment.size <= LiveTuning.SPLIT_MIN_SAMPLES || !nearlyEmpty(text, segment.size))
        return text
    val cut = quietestPoint(segment, 0.25f, 0.75f, prefer = segment.size / 2)
    val left = recognizeSplitting(recognizer, segment.copyOfRange(0, cut))
    val right = recognizeSplitting(recognizer, segment.copyOfRange(cut, segment.size))
    val joined = joinHalves(left, right)
    return if (wordCount(joined) > wordCount(text)) joined else text
}

/**
 * Decodes [samples], never handing the recognizer more than [LiveTuning.MAX_DECODE_SAMPLES]: a
 * longer stretch is cut at its quietest point and the halves decoded.
 */
internal fun recognizeBounded(recognizer: SegmentRecognizer, samples: FloatArray): String {
    if (samples.size <= LiveTuning.MAX_DECODE_SAMPLES) return recognizer.recognize(samples).trim()
    val cut = quietestPoint(samples, 0.25f, 0.75f, prefer = samples.size / 2)
    val left = recognizeBounded(recognizer, samples.copyOfRange(0, cut))
    val right = recognizeBounded(recognizer, samples.copyOfRange(cut, samples.size))
    return joinHalves(left, right)
}

/**
 * The two halves' text as one. A cut in the middle of a word makes both halves say it, so a first
 * word of [right] that repeats the last one of [left] (ignoring case and punctuation) is dropped.
 */
internal fun joinHalves(left: String, right: String): String {
    val a = left.trim()
    var b = right.trim()
    val last = a.substringAfterLast(' ')
    val first = b.substringBefore(' ')
    if (a.isNotEmpty() && b.isNotEmpty() && sameWord(last, first)) {
        b = b.substring(first.length).trim()
    }
    return listOf(a, b).filter { it.isNotEmpty() }.joinToString(" ")
}

private fun sameWord(a: String, b: String): Boolean {
    val x = a.filter { it.isLetterOrDigit() }
    return x.isNotEmpty() && x.equals(b.filter { it.isLetterOrDigit() }, ignoreCase = true)
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

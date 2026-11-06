package dev.keycorrect.feature.voicesherpa

private const val MUSIC_SYMBOLS = "♪♫♩♬"

/** [word] without punctuation, lower-cased: how repeats are compared. */
private fun loopKey(word: String) = word.filter { it.isLetterOrDigit() }.lowercase()

/**
 * The index just past the run of a [group]-word unit repeating from [from] in [keys], when it
 * repeats often enough to be a loop ([LiveTuning.LOOP_REPEATS], [LiveTuning.LOOP_GROUP_REPEATS]);
 * -1 otherwise.
 */
private fun loopEnd(keys: List<String>, from: Int, group: Int): Int {
    var repeats = 1
    while (from + (repeats + 1) * group <= keys.size) {
        val same = (0 until group).all { keys[from + it] == keys[from + repeats * group + it] }
        if (!same) break
        repeats++
    }
    val needed = if (group == 1) LiveTuning.LOOP_REPEATS else LiveTuning.LOOP_GROUP_REPEATS
    return if (repeats >= needed) from + repeats * group else -1
}

/**
 * [text] with each decoder loop collapsed to one copy and tokens made only of music symbols (the
 * model writes noise as "♪♪♪") removed. Text without either comes back unchanged.
 */
internal fun withoutLoops(text: String): String {
    val tokens = text.split(' ').filter { it.isNotEmpty() }
    val words = tokens.filter { w -> !w.all { it in MUSIC_SYMBOLS } }
    val keys = words.map(::loopKey)
    val out = ArrayList<String>(words.size)
    var i = 0
    while (i < words.size) {
        val group = (1..LiveTuning.LOOP_MAX_GROUP).firstOrNull { loopEnd(keys, i, it) >= 0 }
        if (group == null) {
            out += words[i++]
        } else {
            val end = loopEnd(keys, i, group)
            out += words.subList(i, i + group)
            // Keep what the final copy ends with ("cars, cars, cars." -> "cars.").
            out[out.size - 1] =
                words[i + group - 1].trimEnd { !it.isLetterOrDigit() } +
                    words[end - 1].takeLastWhile { !it.isLetterOrDigit() }
            i = end
        }
    }
    return if (out.size == tokens.size) text else out.joinToString(" ")
}

/** Whether [withoutLoops] would change [text]. */
internal fun hasLoop(text: String): Boolean = withoutLoops(text) != text

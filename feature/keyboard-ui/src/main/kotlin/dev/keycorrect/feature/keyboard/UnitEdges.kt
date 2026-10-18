package dev.keycorrect.feature.keyboard

/**
 * The units the selection strip's arrows step over (words, sentences, lines) and how an edge of the
 * selection moves between their boundaries. Pure functions of the text around the selection, built
 * on [TextUnits]'s rules so a move agrees with what the unit buttons select.
 */
internal object UnitEdges {
    private const val ZERO_WIDTH_JOINER = '\u200D'

    /**
     * The [selection] with one [edge] moved a unit [forward] or back, snapped to unit boundaries: a
     * start moves to the start of the previous or next unit, an end to the end of the previous or
     * next one. The selection never inverts: an edge moved past the other collapses it to a caret
     * at the fixed edge. Null where the edge cannot move (no unit that way).
     */
    fun move(
        unit: SelectionUnit,
        text: CharSequence,
        selection: TextSpan,
        edge: SelectionEdge,
        forward: Boolean,
    ): TextSpan? {
        val (start, end) = selection
        val units = unitSpans(unit, text)
        return when {
            edge == SelectionEdge.Start && forward -> {
                val to = units.firstOrNull { it.start > start }?.start
                if (to == null || to >= end) TextSpan(end, end) else TextSpan(to, end)
            }
            edge == SelectionEdge.Start ->
                units.lastOrNull { it.start < start }?.let { TextSpan(it.start, end) }
            forward -> units.firstOrNull { it.end > end }?.let { TextSpan(start, it.end) }
            else -> {
                val to = units.lastOrNull { it.end < end }?.end
                if (to == null || to <= start) TextSpan(start, start) else TextSpan(start, to)
            }
        }
    }

    /** The non-empty units of [text], in order: what the arrows step over. */
    fun unitSpans(unit: SelectionUnit, text: CharSequence): List<TextSpan> =
        when (unit) {
            SelectionUnit.Word -> wordSpans(text)
            SelectionUnit.Sentence -> sentenceSpans(text)
            SelectionUnit.Line -> lineSpans(text)
        }

    /** Runs of word chars; unlike [expandWord] an emoji (any surrogate pair) is a word too. */
    private fun wordSpans(text: CharSequence): List<TextSpan> {
        val out = ArrayList<TextSpan>()
        var i = 0
        while (i < text.length) {
            if (isMovingWordChar(text, i)) {
                val from = i
                while (i < text.length && isMovingWordChar(text, i)) i++
                out.add(TextSpan(from, i))
            } else {
                i++
            }
        }
        return out
    }

    private fun isMovingWordChar(text: CharSequence, i: Int) =
        TextUnits.isWordChar(text, i) || text[i].isSurrogate() || text[i] == ZERO_WIDTH_JOINER

    private fun sentenceSpans(text: CharSequence): List<TextSpan> {
        val starts = sortedSetOf<Int>()
        val first = text.indexOfFirst { !it.isWhitespace() }
        if (first >= 0) starts.add(first)
        for (i in text.indices) {
            if (text[i] == '\n') {
                var k = i + 1
                while (k < text.length && (text[k] == ' ' || text[k] == '\t')) k++
                starts.add(k)
            } else {
                val stop = TextUnits.terminatorEnd(text, i)
                if (stop >= 0) {
                    var k = stop
                    while (k < text.length && text[k].isWhitespace()) k++
                    starts.add(k)
                }
            }
        }
        return starts
            .filter { it < text.length && !text[it].isWhitespace() }
            .mapNotNull { s ->
                TextUnits.sentenceEndFrom(text, s, s).takeIf { it > s }?.let { TextSpan(s, it) }
            }
    }

    private fun lineSpans(text: CharSequence): List<TextSpan> {
        val out = ArrayList<TextSpan>()
        var from = 0
        while (from <= text.length) {
            val brk = text.indexOf('\n', from)
            var end = if (brk < 0) text.length else brk
            if (end > from && text[end - 1] == '\r') end--
            if (end > from) out.add(TextSpan(from, end))
            if (brk < 0) break
            from = brk + 1
        }
        return out
    }
}

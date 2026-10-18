package dev.keycorrect.core.voice

/** Edits the keyboard applies to the editor for dictation. */
sealed interface DictationEdit {
    /** commitText. */
    data class Commit(val text: String) : DictationEdit

    /** setComposingText: replaces the current composing text. */
    data class Composing(val text: String) : DictationEdit

    /** setComposingText("") then finish. */
    data object ClearComposing : DictationEdit

    /**
     * "Delete that": remove what this session committed last (see
     * [DictationAssembler.lastDeletedText]).
     */
    data object DeleteLastDictation : DictationEdit

    /** Commit "\n". */
    data object NewLine : DictationEdit
}

/**
 * Turns one session's results into editor edits. [textBefore] is up to ~64 chars before the cursor
 * when dictation starts (null when unreadable, e.g. sensitive fields — dictation is never offered
 * there).
 *
 * The stable prefix of each partial is committed word by word and the rest is shown as composing
 * text; a final commits what is left and clears the composing text. A session may hold several
 * utterances (a final ends one). Engines that already punctuate and capitalise their text get only
 * the leading capital and space fixed.
 */
class DictationAssembler(
    textBefore: String?,
    private val autoCapitalize: Boolean = true,
    private val autoSpace: Boolean = true,
    private val commands: SpokenCommands = SpokenCommands.DEFAULT,
) {
    private class Cursor(var lastChar: Char?, var capNext: Boolean, var emitted: Boolean = false) {
        fun copy() = Cursor(lastChar, capNext, emitted)
    }

    private val before = textBefore.orEmpty().takeLast(TAIL)
    private val committed = StringBuilder()
    private val utteranceStarts = ArrayList<Int>()
    private var utteranceStart = 0
    private var cursor = cursorFor(before)

    // The current utterance.
    private var rawCommitted = 0
    private var styled = false
    private var composingShown = false
    private var lastComposing = ""

    /** Everything this session committed (minus what "delete that" removed), for learning. */
    val committedText: String
        get() = committed.toString()

    /**
     * The text removed by the last [DictationEdit.DeleteLastDictation], so the keyboard knows how
     * much to delete.
     */
    var lastDeletedText: String = ""
        private set

    fun onPartial(text: String, stablePrefixLength: Int): List<DictationEdit> {
        styled = styled || isStyled(text)
        val edits = ArrayList<DictationEdit>()
        val end = commitBoundary(text, stablePrefixLength)
        if (end > rawCommitted) {
            val committedNow =
                record(
                    format(text.substring(rawCommitted, end), cursor, applyCommands = true),
                    edits,
                )
            rawCommitted = end
            if (committedNow) composingShown = false // a commit replaces the composing text
        }
        val preview =
            format(
                    text.substring(minOf(rawCommitted, text.length)),
                    cursor.copy(),
                    applyCommands = false,
                )
                .joinToString("") { (it as DictationEdit.Commit).text }
        when {
            preview.isEmpty() -> if (composingShown) edits += clearComposing()
            !composingShown || preview != lastComposing -> {
                edits += DictationEdit.Composing(preview)
                composingShown = true
                lastComposing = preview
            }
        }
        return edits
    }

    fun onFinal(text: String): List<DictationEdit> {
        val edits = ArrayList<DictationEdit>()
        if (composingShown) edits += clearComposing()
        if (rawCommitted == 0 && commands.isDeleteThat(text)) {
            deleteLast(edits)
        } else {
            styled = styled || isStyled(text)
            record(
                format(
                    text.substring(minOf(rawCommitted, text.length)),
                    cursor,
                    applyCommands = true,
                ),
                edits,
            )
        }
        endUtterance()
        return edits
    }

    fun cancel(): List<DictationEdit> {
        val edits = if (composingShown) listOf(DictationEdit.ClearComposing) else emptyList()
        endUtterance()
        return edits
    }

    private fun clearComposing(): DictationEdit {
        composingShown = false
        lastComposing = ""
        return DictationEdit.ClearComposing
    }

    private fun endUtterance() {
        if (committed.length > utteranceStart) utteranceStarts += utteranceStart
        utteranceStart = committed.length
        rawCommitted = 0
        styled = false
        composingShown = false
        lastComposing = ""
        cursor.emitted = false
        cursor.capNext = autoCapitalize && cursor.lastChar?.let { it in SENTENCE_END } == true
    }

    private fun deleteLast(edits: MutableList<DictationEdit>) {
        val start = utteranceStarts.removeLastOrNull() ?: return
        lastDeletedText = committed.substring(start)
        committed.setLength(start)
        utteranceStart = start
        cursor = cursorFor(before + committed.takeLast(TAIL))
        edits += DictationEdit.DeleteLastDictation
    }

    /** Adds [out] to the session's committed text and [edits]; true when anything was committed. */
    private fun record(out: List<DictationEdit>, edits: MutableList<DictationEdit>): Boolean {
        for (edit in out) {
            when (edit) {
                is DictationEdit.Commit -> committed.append(edit.text)
                DictationEdit.NewLine -> committed.append('\n')
                else -> Unit
            }
            edits += edit
        }
        return out.isNotEmpty()
    }

    /**
     * The raw-text offset up to which a partial may be committed: a word boundary inside the stable
     * prefix.
     */
    private fun commitBoundary(text: String, stablePrefixLength: Int): Int {
        val stable = stablePrefixLength.coerceIn(0, text.length)
        if (stable <= rawCommitted) return rawCommitted
        var end = stable
        if (stable < text.length && !text[stable].isWhitespace()) {
            end = (0 until stable).lastOrNull { text[it].isWhitespace() } ?: 0
        }
        end = trimEnd(text, end, rawCommitted)
        val last =
            TOKEN.findAll(text.substring(rawCommitted, maxOf(end, rawCommitted))).lastOrNull()
        if (last != null && commands.startsCommand(last.value))
            end = trimEnd(text, rawCommitted + last.range.first, rawCommitted)
        return maxOf(end, rawCommitted)
    }

    private fun format(raw: String, cur: Cursor, applyCommands: Boolean): List<DictationEdit> {
        val edits = ArrayList<DictationEdit>()
        val sb = StringBuilder()
        val tokens = TOKEN.findAll(raw).map { it.value }.toList()
        var i = 0
        while (i < tokens.size) {
            val match = if (applyCommands) commands.match(tokens, i) else null
            if (match != null) {
                applyCommand(match.command.action, cur, sb, edits)
                i += match.length
                continue
            }
            val word = if (cur.capNext) capitalise(tokens[i]) else tokens[i]
            if ((cur.emitted || autoSpace) && needsSpace(cur.lastChar)) sb.append(' ')
            sb.append(word)
            cur.lastChar = word.last()
            cur.emitted = true
            cur.capNext = autoCapitalize && !styled && word.last() in SENTENCE_END
            i++
        }
        if (sb.isNotEmpty()) edits += DictationEdit.Commit(sb.toString())
        return edits
    }

    private fun applyCommand(
        action: CommandAction,
        cur: Cursor,
        sb: StringBuilder,
        edits: MutableList<DictationEdit>,
    ) {
        when (action) {
            is CommandAction.Punctuation -> {
                sb.append(action.text)
                cur.lastChar = action.text.last()
                if (action.endsSentence) cur.capNext = autoCapitalize
            }
            is CommandAction.NewLines -> {
                if (sb.isNotEmpty()) edits += DictationEdit.Commit(sb.toString())
                sb.clear()
                repeat(action.count) { edits += DictationEdit.NewLine }
                cur.lastChar = '\n'
                cur.capNext = autoCapitalize
            }
            CommandAction.DeleteThat -> Unit
        }
        cur.emitted = true
    }

    private fun cursorFor(tail: String) =
        Cursor(tail.lastOrNull(), autoCapitalize && startsSentence(tail))

    private companion object {
        const val TAIL = 64
    }
}

private val TOKEN = Regex("\\S+")
private const val SENTENCE_END = ".?!"
private const val ATTACHED_PUNCTUATION = ".,?!;:"

/** Opening brackets and quotes: dictation starts right after them. */
private const val NO_SPACE_AFTER = "([{\u201C\u2018\u00AB\u00BF\u00A1\"'"

private fun startsSentence(tail: String): Boolean {
    if (tail.isBlank() || tail.last() == '\n') return true
    return tail.trimEnd(' ', '\t').last() in SENTENCE_END
}

private fun needsSpace(last: Char?) =
    last != null && !last.isWhitespace() && last !in NO_SPACE_AFTER

private fun capitalise(word: String) = word.replaceFirstChar { it.uppercaseChar() }

/**
 * Engines that punctuate or capitalise inside the text (Whisper, Moonshine) shouldn't be changed
 * again.
 */
private fun isStyled(raw: String): Boolean {
    val tokens = TOKEN.findAll(raw).map { it.value }.toList()
    return tokens.any { it.last() in ATTACHED_PUNCTUATION } ||
        tokens.drop(1).any { it.first().isUpperCase() }
}

private fun trimEnd(text: String, end: Int, floor: Int): Int {
    var e = end
    while (e > floor && text[e - 1].isWhitespace()) e--
    return e
}

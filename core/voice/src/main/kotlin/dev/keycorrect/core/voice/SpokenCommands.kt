package dev.keycorrect.core.voice

/** What a spoken command does to the dictated text. */
sealed interface CommandAction {
    /** Inserts [text] attached to the previous word; a sentence end capitalises the next word. */
    data class Punctuation(val text: String, val endsSentence: Boolean = false) : CommandAction

    /** Inserts [count] line breaks. */
    data class NewLines(val count: Int) : CommandAction

    /** "Delete that": removes the last dictation, but only when it is the whole utterance. */
    data object DeleteThat : CommandAction
}

/** One spoken [phrase] (lowercase words separated by single spaces) and what it does. */
data class SpokenCommand(val phrase: String, val action: CommandAction) {
    internal val words: List<String> = phrase.lowercase().split(' ')
}

/** A command found at some token index, spanning [length] tokens. */
data class CommandMatch(val command: SpokenCommand, val length: Int)

/**
 * The table of spoken commands. Matching is case-insensitive, whole-word, and ignores punctuation
 * an engine attaches to a word ("New line." matches "new line"). Add commands with [plus].
 */
class SpokenCommands(entries: List<SpokenCommand>) {
    private val entries = entries.sortedByDescending { it.words.size }
    private val starters = entries.filter { it.words.size > 1 }.map { it.words.first() }.toSet()

    operator fun plus(more: List<SpokenCommand>) = SpokenCommands(entries + more)

    /**
     * The longest in-text command at [index] in [tokens]; [DeleteThat][CommandAction.DeleteThat] is
     * not in-text.
     */
    fun match(tokens: List<String>, index: Int): CommandMatch? {
        for (command in entries) {
            if (command.action !is CommandAction.DeleteThat && matchesAt(command, tokens, index)) {
                return CommandMatch(command, command.words.size)
            }
        }
        return null
    }

    /**
     * True when [token] could be the first word of a longer command, so a partial must wait for the
     * next word.
     */
    fun startsCommand(token: String): Boolean = normalise(token) in starters

    /** True when [text] is nothing but a "delete that" command. */
    fun isDeleteThat(text: String): Boolean {
        val words = tokens(text)
        return entries.any { it.action is CommandAction.DeleteThat && it.words == words }
    }

    private fun matchesAt(command: SpokenCommand, tokens: List<String>, index: Int): Boolean {
        if (index + command.words.size > tokens.size) return false
        return command.words.indices.all { normalise(tokens[index + it]) == command.words[it] }
    }

    private fun tokens(text: String) =
        text.split(WHITESPACE).filter { it.isNotEmpty() }.map(::normalise)

    companion object {
        private val WHITESPACE = Regex("\\s+")

        internal fun normalise(token: String): String =
            token.lowercase().trim { !it.isLetterOrDigit() }

        val DEFAULT =
            SpokenCommands(
                listOf(
                    SpokenCommand("new line", CommandAction.NewLines(1)),
                    SpokenCommand("newline", CommandAction.NewLines(1)),
                    SpokenCommand("new paragraph", CommandAction.NewLines(2)),
                    SpokenCommand("period", CommandAction.Punctuation(".", endsSentence = true)),
                    SpokenCommand("full stop", CommandAction.Punctuation(".", endsSentence = true)),
                    SpokenCommand("comma", CommandAction.Punctuation(",")),
                    SpokenCommand(
                        "question mark",
                        CommandAction.Punctuation("?", endsSentence = true),
                    ),
                    SpokenCommand(
                        "exclamation mark",
                        CommandAction.Punctuation("!", endsSentence = true),
                    ),
                    SpokenCommand(
                        "exclamation point",
                        CommandAction.Punctuation("!", endsSentence = true),
                    ),
                    SpokenCommand("colon", CommandAction.Punctuation(":")),
                    SpokenCommand("semicolon", CommandAction.Punctuation(";")),
                    SpokenCommand("delete that", CommandAction.DeleteThat),
                )
            )
    }
}

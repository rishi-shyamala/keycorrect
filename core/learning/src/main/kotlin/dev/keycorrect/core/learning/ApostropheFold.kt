package dev.keycorrect.core.learning

/**
 * The one apostrophe fold for comparing and keying words: the typographic apostrophes of
 * [SentenceContext.isApostrophe] plus the acute accent, backtick and prime typed in their place all
 * become `'`. Every learned key goes through it, so anything compared against learned words must
 * too.
 *
 * Word boundaries are a different question and use [SentenceContext.isApostrophe]: a backtick does
 * not join a word, it only matches `'` once a word is being compared.
 */
fun foldApostrophe(c: Char): Char =
    when (c) {
        '’',
        '‘',
        'ʼ',
        '´',
        '`',
        '′' -> '\''
        else -> c
    }

/** [s] with [foldApostrophe] applied to every char; [s] itself when nothing folds. */
fun foldApostrophes(s: String): String =
    if (s.all { foldApostrophe(it) == it }) s
    else String(CharArray(s.length) { foldApostrophe(s[it]) })

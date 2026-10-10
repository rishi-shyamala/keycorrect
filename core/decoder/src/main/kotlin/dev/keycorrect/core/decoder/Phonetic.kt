package dev.keycorrect.core.decoder

/** Letter classes of cognitive (non-motor) misspellings. */
internal object Phonetic {
    fun isVowel(c: Char): Boolean = c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u'

    /**
     * True if a writer might put [typed] where the word has [word] because the two sound alike in
     * unstressed or ambiguous syllables: any two different vowels, and y/i.
     */
    fun confusable(typed: Char, word: Char): Boolean {
        if (typed == word) return false
        if (isVowel(typed) && isVowel(word)) return true
        return (typed == 'y' && word == 'i') || (typed == 'i' && word == 'y')
    }
}

package dev.keycorrect.core.learning

import dev.keycorrect.core.engineapi.FieldClass

/**
 * Where the keyboard sends learning signals. Calls come from the main thread and must return
 * immediately (implementations hand off to a learning thread). The keyboard calls these only after
 * its privacy gate: never for sensitive fields, no-learning fields, incognito, or when learning is
 * turned off.
 */
interface LearningSink {
    fun onEvent(event: LearningEvent)

    /** One committed word for the recent-sentence buffer (decision D10). */
    fun onSentenceWord(sessionId: Long, word: String, sentenceEnd: Boolean, timeMillis: Long)

    /**
     * Like the four-argument form, with the class of the field the word was typed in. Sentence
     * words are only ever recorded for [FieldClass.Text]; the keyboard should call this form.
     */
    fun onSentenceWord(
        sessionId: Long,
        word: String,
        sentenceEnd: Boolean,
        timeMillis: Long,
        field: FieldClass,
    ) {
        if (field == FieldClass.Text) onSentenceWord(sessionId, word, sentenceEnd, timeMillis)
    }

    companion object {
        val None: LearningSink =
            object : LearningSink {
                override fun onEvent(event: LearningEvent) = Unit

                override fun onSentenceWord(
                    sessionId: Long,
                    word: String,
                    sentenceEnd: Boolean,
                    timeMillis: Long,
                ) = Unit
            }
    }
}

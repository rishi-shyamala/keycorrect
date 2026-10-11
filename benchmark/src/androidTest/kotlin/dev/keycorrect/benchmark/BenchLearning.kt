package dev.keycorrect.benchmark

import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.learning.AutocorrectReverted
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.DeviceClass
import dev.keycorrect.core.learning.InputProfile
import dev.keycorrect.core.learning.LearningEvent
import dev.keycorrect.core.learning.RevertKind
import dev.keycorrect.core.learning.ShownSuggestion
import dev.keycorrect.core.learning.TouchSample
import dev.keycorrect.core.learning.WordCommitted
import dev.keycorrect.core.learning.classifier.OutcomeClassifier
import dev.keycorrect.core.learning.model.PersonalModel
import kotlin.random.Random

/**
 * Simulated learning input: a deterministic stream of word events from a typist whose taps land a
 * little right of and below the key centres, who now and then types names the dictionary lacks, and
 * who lets autocorrections stand. Fed through [OutcomeClassifier] into a [PersonalModel] as the
 * app's learning thread does, so the model ends up with user n-grams, correction memory, a touch
 * profile and user words.
 */
object BenchLearning {
    val PROFILE = InputProfile(DeviceClass.Tablet, false, "qwerty@bench", FieldClass.Text)

    /** Events absorbed before the personalised decoder benchmark starts. */
    const val TRAIN_WORDS = 3_000

    const val EVENT_GAP_MILLIS = 1_500L

    const val TRAIN_END_MILLIS = TRAIN_WORDS * EVENT_GAP_MILLIS

    private const val SEED = 7_171
    private const val BIAS_X = 0.12f
    private const val BIAS_Y = 0.06f
    private val NAMES = listOf("rishi", "keycorrect", "galaxy")
    private const val NEIGHBOURS = "qwertyuiopasdfghjklzxcvbnm"

    /** A model that has absorbed [TRAIN_WORDS] events and published its snapshot. */
    fun newTrainedModel(): PersonalModel = PersonalModel().also { train(it, TRAIN_WORDS) }

    fun train(model: PersonalModel, words: Int) {
        val classifier = OutcomeClassifier()
        var last = 0L
        for (e in events(words, startMillis = 0L)) {
            model.apply(classifier.classify(e), e.timeMillis)
            last = e.timeMillis
        }
        model.publishNow(last)
    }

    /** [count] events one [EVENT_GAP_MILLIS] apart from [startMillis]; same input, same events. */
    fun events(count: Int, startMillis: Long): List<LearningEvent> {
        val rnd = Random(SEED)
        val vocabulary = BenchInputs.vocabulary
        val history = ArrayDeque<String>()
        return List(count) { i ->
            val word = vocabulary[(i * 7) % vocabulary.size]
            val time = startMillis + (i + 1) * EVENT_GAP_MILLIS
            val context = history.toList()
            val session = 1L + i / WORDS_PER_SESSION
            val event =
                when {
                    i % 25 == 24 -> reverted(time, context, word, rnd)
                    i % 5 == 2 -> name(time, context, NAMES[(i / 5) % NAMES.size], session, rnd)
                    i % 5 == 3 -> autocorrected(time, context, word, session, rnd)
                    i % 5 == 4 -> picked(time, context, word, session, rnd)
                    else -> typed(time, context, word, session, rnd)
                }
            history.addLast(word)
            if (history.size > 2) history.removeFirst()
            event
        }
    }

    private fun shown(word: String) =
        listOf(
            ShownSuggestion(word, 0, word.length, 400),
            ShownSuggestion(word + "s", 1, word.length, 400),
            ShownSuggestion(word + "ed", 2, word.length, 400),
        )

    private fun touches(text: String, rnd: Random): List<TouchSample?> = text.map { c ->
        TouchSample(
            c,
            BIAS_X + (rnd.nextFloat() - 0.5f) * 0.2f,
            BIAS_Y + (rnd.nextFloat() - 0.5f) * 0.2f,
        )
    }

    private fun typed(time: Long, ctx: List<String>, word: String, session: Long, rnd: Random) =
        WordCommitted(
            time,
            PROFILE,
            ctx,
            typed = word,
            committed = word,
            method = CommitMethod.Typed,
            shown = shown(word),
            touches = touches(word, rnd),
            sentenceInitial = ctx.isEmpty(),
            sessionId = session,
        )

    private fun name(time: Long, ctx: List<String>, name: String, session: Long, rnd: Random) =
        WordCommitted(
            time,
            PROFILE,
            ctx,
            typed = name,
            committed = name,
            method = CommitMethod.Typed,
            shown = shown("name"),
            touches = touches(name, rnd),
            committedIsKnown = false,
            typedIsKnown = false,
            sessionId = session,
        )

    private fun autocorrected(
        time: Long,
        ctx: List<String>,
        word: String,
        session: Long,
        rnd: Random,
    ): WordCommitted {
        val typo = substitute(word, word.length / 2)
        return WordCommitted(
            time,
            PROFILE,
            ctx,
            typed = typo,
            committed = word,
            method = CommitMethod.Autocorrected,
            shown = shown(word),
            touches = touches(typo, rnd),
            sessionId = session,
        )
    }

    private fun picked(time: Long, ctx: List<String>, word: String, session: Long, rnd: Random) =
        WordCommitted(
            time,
            PROFILE,
            ctx,
            typed = word.take(3),
            committed = word,
            method = CommitMethod.SuggestionPicked,
            pickedRank = 1,
            shown = shown(word),
            touches = touches(word.take(3), rnd),
            sessionId = session,
        )

    private fun reverted(time: Long, ctx: List<String>, word: String, rnd: Random) =
        AutocorrectReverted(
            time,
            PROFILE,
            ctx,
            typed = substitute(word, rnd.nextInt(word.length)),
            corrected = word,
            kind = RevertKind.ImmediateBackspace,
            replacement = word,
        )

    private fun substitute(w: String, at: Int): String {
        val c = w.toCharArray()
        val j = NEIGHBOURS.indexOf(c[at])
        if (j >= 0) c[at] = NEIGHBOURS[(j + 1) % NEIGHBOURS.length]
        return String(c)
    }

    private const val WORDS_PER_SESSION = 100
}

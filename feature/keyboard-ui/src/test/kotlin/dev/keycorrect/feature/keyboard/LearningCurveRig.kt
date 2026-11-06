package dev.keycorrect.feature.keyboard

import android.text.InputType
import dev.keycorrect.core.decoder.AutocorrectConfig
import dev.keycorrect.core.decoder.ClassicConfig
import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.decoder.rerank.NeuralReranker
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.learning.LearningEvent
import dev.keycorrect.core.learning.LearningSink
import dev.keycorrect.core.learning.classifier.OutcomeClassifier
import dev.keycorrect.core.learning.model.PersonalModel
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.prediction.PredictionBackend
import dev.keycorrect.feature.keyboard.prediction.Predictor
import dev.keycorrect.feature.keyboard.prediction.ShortcutRule
import dev.keycorrect.feature.keyboard.prediction.SlotKind
import dev.keycorrect.feature.keyboard.prediction.StripModel

/** What the strip offered right after the space that follows a word, before the next one. */
internal class Boundary(
    /** Index of the word that is about to be typed (the one the strip should predict). */
    val index: Int,
    val expected: String,
    /** The three slots, left to right, as (lowercased text, kind); null when empty. */
    val left: Pair<String, SlotKind>?,
    val centre: Pair<String, SlotKind>?,
    val right: Pair<String, SlotKind>?,
    /** The next true words from [index] on, for the phrase check. */
    val upcoming: List<String>,
) {
    private val slots = listOf(centre, left, right) // best first, like a user reads the ranking

    /** 1-based rank (centre 1, left 2, right 3) of [word] among the slots, 0 when not offered. */
    fun rankOf(word: String): Int {
        for ((i, s) in slots.withIndex()) {
            if (s == null) continue
            val first =
                if (s.second == SlotKind.Phrase) s.first.split(' ').firstOrNull().orEmpty()
                else s.first
            if (first == word) return if (i == 0) 1 else if (i == 1) 2 else 3
        }
        return 0
    }

    /** Like [rankOf] but a phrase slot does not count (only plain word slots). */
    fun wordRankOf(word: String): Int {
        for ((i, s) in slots.withIndex()) {
            if (s != null && s.second != SlotKind.Phrase && s.first == word) {
                return if (i == 0) 1 else if (i == 1) 2 else 3
            }
        }
        return 0
    }

    val top1: Boolean
        get() = rankOf(expected) == 1

    val top3: Boolean
        get() = rankOf(expected) in 1..3

    val top3WordsOnly: Boolean
        get() = wordRankOf(expected) in 1..3

    val phraseWords: List<String>?
        get() =
            listOf(left, centre, right)
                .firstOrNull { it?.second == SlotKind.Phrase }
                ?.first
                ?.split(' ')
                ?.filter { it.isNotEmpty() }

    val phraseOffered: Boolean
        get() = phraseWords != null

    /** How many leading words of the phrase equal the next true words (0 without a phrase). */
    val phraseMatched: Int
        get() {
            val p = phraseWords ?: return 0
            var n = 0
            while (n < p.size && n < upcoming.size && p[n] == upcoming[n]) n++
            return n
        }

    /** The phrase's words are the next 2+ true words (a prefix match). */
    val phraseCorrect: Boolean
        get() = phraseMatched >= 2

    fun describe(): String =
        listOf(left, centre, right).joinToString(" | ") { it?.let { s -> s.first } ?: "-" }
}

/** The result of typing one whole snippet. */
internal class PassResult(
    val boundaries: List<Boundary>,
    val tokens: Int,
    val tokensCorrect: Int,
    /** (expected token, what the field holds) for each committed word that differs. */
    val damaged: List<Pair<String, String>>,
    val simulatedMs: Long,
)

/**
 * The real keyboard pipeline, driven key by key: [KeyboardController] over the real [ClassicEngine]
 * and the real dictionary, a [SyncLearningSink] doing what the app's `LearningHost` does, and a
 * fake clock. Nothing is shortcut: every character is a tap at its key centre.
 */
internal class TypingRig(
    dictionary: Dictionary,
    private val reranker: NeuralReranker? = null,
    private val settings: KeyboardSettings = KeyboardSettings(),
    private val msPerKey: Long = 300,
    private val msBetweenSentences: Long = 1_500,
) {
    /** The clock, used for both the controller's uptime and wall clock; only moves when typing. */
    var now = START_MS
        private set

    val editor = SimEditor()
    val model = PersonalModel()
    private val classifier = OutcomeClassifier()

    /** The learned user dictionary (what `LearningHost.drainUserWords` adds to the real one). */
    private val userWords = LinkedHashSet<String>()

    // The app publishes trailing updates from a tick `publishDelayMillis` after the first one.
    private var tickAt = Long.MAX_VALUE
    var learnedEvents = 0
        private set

    private val sink =
        object : LearningSink {
            /**
             * The app queues this and applies it on a learning thread (`LearningHost.handleEvent`);
             * here it is applied at once, so the model is never behind the decoder.
             */
            override fun onEvent(event: LearningEvent) {
                if (event.profile.field in NEVER_LEARN_FIELDS) return
                val updates = classifier.classify(event)
                model.apply(updates, event.timeMillis)
                model.pendingUserDictionaryAdds().forEach { userWords += it.word }
                learnedEvents++
                if (tickAt == Long.MAX_VALUE) tickAt = now + PUBLISH_DELAY_MS
            }

            override fun onSentenceWord(
                sessionId: Long,
                word: String,
                sentenceEnd: Boolean,
                timeMillis: Long,
            ) {
                if (!settings.sentenceBufferEnabled) return
                model.recordSentenceWord(sessionId, word, sentenceEnd, timeMillis)
            }
        }

    private val backend =
        object : PredictionBackend {
            override var predictor: Predictor? = null

            override fun shortcutFor(trigger: String): ShortcutRule? = null

            override fun isKnownWord(word: String): Boolean {
                val plain = word.lowercase()
                return plain in userWords ||
                    model.snapshot().isUserWord(plain) ||
                    dictionary.idsForKey(plain).isNotEmpty() ||
                    dictionary.wordId(word) >= 0
            }

            override fun addWord(word: String) {
                userWords += word
            }
        }

    val controller =
        KeyboardController(
            editor,
            KeyboardComposer(BuiltInLayouts.repository()),
            testEnv(backend),
            clock = { now },
            learning = sink,
            wallClock = { now },
        )

    init {
        val engine =
            ClassicEngine(
                dictionary,
                ClassicConfig(
                    autocorrect = AutocorrectConfig(nonWordTieRule = settings.nonWordTieRule)
                ),
                userWords = { userWords },
                // PredictionHost.installEngine: personalise every decode with the latest snapshot.
                personalization = { model.snapshot() },
                touchKey = { controller.currentTouchKey },
            )
        engine.warmUp()
        backend.predictor =
            object : Predictor {
                override fun predict(
                    request: dev.keycorrect.core.engineapi.PredictRequest,
                    stale: () -> Boolean,
                ) = engine.predictNow(request, stale)

                override fun refine(
                    request: dev.keycorrect.core.engineapi.PredictRequest,
                    result: dev.keycorrect.core.engineapi.PredictResult,
                    stale: () -> Boolean,
                ) = reranker?.refine(request, result, stale) { engine.predictNow(it, stale) }
            }
    }

    private lateinit var centres: Map<String, Pair<Float, Float>>

    /** A fresh empty text field with learning on (what a new message box is). */
    fun newField() {
        editor.setText("")
        controller.onStartInput(FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0), settings)
        val geometry = KeyboardGeometry(controller.layer, WIDTH, HEIGHT)
        controller.onGeometry(geometry)
        centres =
            geometry.slots
                .mapNotNull { slot ->
                    (slot.key.action as? KeyAction.Text)?.let {
                        it.text to (slot.centerX to slot.centerY)
                    }
                }
                .toMap()
    }

    fun idle(ms: Long) {
        now += ms
        tickIfDue()
    }

    private fun tickIfDue() {
        if (now >= tickAt) {
            // LearningHost.handleTick
            model.retryPendingPreferences(now)
            model.publishNow(now)
            tickAt = Long.MAX_VALUE
        }
    }

    private fun press(action: KeyAction, touch: TouchPoint? = null) {
        now += msPerKey
        tickIfDue()
        controller.onKey(action, touch)
        editor.echo(controller)
    }

    private fun tap(c: Char) {
        val centre = centres[c.toString()]
        press(
            KeyAction.Text("$c"),
            centre?.let { TouchPoint(it.first, it.second, now + msPerKey) },
        )
    }

    private fun space() = press(KeyAction.Space)

    private fun strip(): StripModel = controller.strip

    /** Types [tokens] (words with their punctuation attached), measuring before each word. */
    fun typeSnippet(tokens: List<String>, words: List<String>): PassResult {
        val start = now
        newField()
        val boundaries = ArrayList<Boundary>()
        val damaged = ArrayList<Pair<String, String>>()
        var correct = 0
        for ((i, token) in tokens.withIndex()) {
            if (i > 0) {
                val s = strip()
                fun slot(x: dev.keycorrect.feature.keyboard.prediction.StripSlot?) = x?.let {
                    it.text.lowercase() to it.kind
                }
                boundaries +=
                    Boundary(
                        i,
                        words[i],
                        slot(s.left),
                        slot(s.centre),
                        slot(s.right),
                        words.drop(i),
                    )
            }
            token.forEach { tap(it) }
            if (i < tokens.size - 1) {
                space()
                val field = editor.text.toString()
                val got = field.dropLast(1).substringAfterLast(' ')
                if (got.equals(token, ignoreCase = true)) correct++ else damaged += token to got
                if (token.endsWith('.')) idle(msBetweenSentences)
            } else {
                correct++ // the last token has no separator; it stays composing
            }
        }
        return PassResult(boundaries, tokens.size, correct, damaged, now - start)
    }

    companion object {
        const val START_MS = 1_700_000_000_000L
        const val WIDTH = 1080f
        const val HEIGHT = 520f

        /** `LearningHostConfig.publishDelayMillis`. */
        const val PUBLISH_DELAY_MS = 1_500L

        /** `LearningHost.NEVER_LEARN_FIELDS`. */
        val NEVER_LEARN_FIELDS = setOf(FieldClass.Email, FieldClass.Url, FieldClass.PostalAddress)
    }
}

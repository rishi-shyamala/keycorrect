package dev.keycorrect.tools.eval

import dev.keycorrect.core.decoder.ClassicConfig
import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.engineapi.ComposingInput
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.TypingContext
import dev.keycorrect.core.learning.RevertKind
import dev.keycorrect.core.learning.classifier.ClassifierConfig
import dev.keycorrect.core.learning.classifier.OutcomeClassifier
import dev.keycorrect.core.learning.model.PersonalModel
import dev.keycorrect.core.learning.model.PersonalModelConfig
import java.util.Random
import kotlin.math.hypot
import kotlin.random.Random as KRandom

/** Counters of one window of the learning curve (words `from` until `to`). */
class LearnWindow(val from: Int, val to: Int) {
    var words = 0
    var personalWords = 0

    /** Final text equals the target (after the user's reaction). */
    var finalCorrect = 0
    var personalFinalCorrect = 0

    /** What the keyboard produced before the user reacted. */
    var keyboardCorrect = 0
    var personalKeyboardCorrect = 0

    /** What the touches produced, before the keyboard did anything. */
    var literalCorrect = 0
    var personalLiteralCorrect = 0

    /** Literal was right and the keyboard changed it. */
    var falseCorrections = 0
    var personalFalseCorrections = 0

    /** The user undid an autocorrection (any way). */
    var reverts = 0
    var personalReverts = 0

    /** Of [reverts]: a correct word was changed and the user restored it. */
    var correctWordReverts = 0
    var taps = 0
    var retypes = 0

    /** Target (or the right literal) on the 3-slot strip. */
    var top3 = 0
    var personalTop3 = 0

    /**
     * Words whose literal was reverted before (and right again) / the keyboard changed it anyway.
     */
    var afterRevertOccurrences = 0
    var afterRevertCorrected = 0
    var personalAfterRevertCorrected = 0

    // Touch model at the end of the window (key units).
    var learnedKeys = 0
    var learnedMeanDx = Double.NaN
    var learnedMeanDy = Double.NaN
    var learnedError = Double.NaN
    /**
     * Keys with >= 30 confirmed taps (intended key = registered key): the best the classifier's
     * evidence allows.
     */
    var attainableKeys = 0
    var attainableError = Double.NaN

    /**
     * Offset of all taps relative to the key the user meant (what a perfect estimator would learn).
     */
    var oracleError = Double.NaN

    val label: String
        get() = "${short(from)}-${short(to)}"

    private fun short(n: Int) = if (n % 1000 == 0) "${n / 1000}k" else n.toString()
}

/** Per-word counters of one personal word. */
class WordTally {
    var occurrences = 0
    var literalRight = 0
    var keyboardRight = 0
    var finalRight = 0
    var falseCorrections = 0
    var reverts = 0
}

/** What the learned user dictionary ended up holding. */
class UserWordBreakdown(
    val total: Int,
    val personal: Int,
    /** Other words the user really typed (other out-of-vocabulary names). */
    val otherTargets: Int,
    /** Words that were never a target: uncorrected typos that got promoted. */
    val pollution: List<String>,
    val personalMissing: List<String>,
)

class ArmResult(
    val name: String,
    val learning: Boolean,
    val windows: List<LearnWindow>,
    val perWord: Map<String, WordTally>,
    val perWordLastWindow: Map<String, WordTally>,
    /** Strings describing wrongly corrected personal words in the last window. */
    val examples: List<String>,
    val userWords: UserWordBreakdown?,
    val suppressions: Int,
    val seconds: Double,
    /** One CSV line per word (first `traceWords` words); empty unless tracing. */
    val trace: List<String> = emptyList(),
)

/**
 * One closed-loop run: a stream of words typed by the personal user through a [ClassicEngine], the
 * simulated reaction to each word, and (for the learning arm) the resulting events fed through
 * [OutcomeClassifier] into a live [PersonalModel] that personalises the next decode.
 *
 * Wiring (as in the app): `personalization = { model.snapshot() }` (an immutable snapshot, so the
 * engine rebuilds its spatial models when the learned offsets change), `userWords` = the
 * user-dictionary adds drained from the model, `touchKey` = the profile's key, and
 * `rankingFeatureProvider` -> `engine.featuresFor` for the request of the current word. Events are
 * applied one by one at their simulated time and the model publishes after every word (the idle
 * flush the model's docs ask for).
 */
class LearnArm(
    private val name: String,
    private val learning: Boolean,
    private val dictionary: Dictionary,
    private val config: ClassicConfig,
    private val keyboard: EvalKeyboard,
    private val typistConfig: TypistConfig,
    private val user: PersonalUser,
    private val reactions: ReactionModel,
    private val seed: Long,
    private val modelConfig: PersonalModelConfig = PersonalModelConfig(),
    private val classifierConfig: ClassifierConfig = ClassifierConfig(),
    private val log: (String) -> Unit = {},
    private val traceWords: Int = 0,
) {
    private val profile = EventBuilder.DEFAULT_PROFILE
    private val typist = Typist(keyboard, typistConfig)

    private val model = PersonalModel(modelConfig)
    private val classifier = OutcomeClassifier(classifierConfig)
    private var userList: List<String> = emptyList()
    private val userKeys = HashSet<String>()
    private var current: PredictRequest? = null

    private val engine =
        if (learning) {
            ClassicEngine(
                dictionary,
                config,
                userWords = { userList },
                personalization = { model.snapshot() },
                touchKey = { profile.touchKey },
                random = KRandom(seed),
            )
        } else {
            ClassicEngine(dictionary, config, random = KRandom(seed))
        }

    init {
        if (learning) {
            model.rankingFeatureProvider = { typed, _, candidate ->
                val req = current
                if (req != null && req.composing.text == typed) {
                    engine.featuresFor(req, candidate)
                } else {
                    null
                }
            }
        }
    }

    private fun inDictionary(word: String): Boolean {
        val node = dictionary.findNode(word.replace('’', '\'').lowercase())
        return node >= 0 && dictionary.isTerminal(node)
    }

    private fun isKnown(word: String): Boolean =
        inDictionary(word) || PersonalUser.fold(word) in userKeys

    private val builder = EventBuilder(profile, keyboard, ::isKnown)

    fun run(stream: List<ParsedSentence>, limit: Int, windowEnds: List<Int>): ArmResult {
        val started = System.nanoTime()
        val windows = ArrayList<LearnWindow>()
        var prev = 0
        for (e in windowEnds) {
            windows.add(LearnWindow(prev, e))
            prev = e
        }
        val perWord = HashMap<String, WordTally>()
        val perWordLast = HashMap<String, WordTally>()
        val targets = HashSet<String>()
        val examples = ArrayList<String>()
        val reverted = HashSet<String>()
        val trace = ArrayList<String>()
        // Touch statistics of the typed stream: confirmed taps (intended key = registered key,
        // relative to that key) and all taps relative to the key meant. Cognitive slips excluded.
        val conf = TouchSums()
        val oracle = TouchSums()

        var wi = 0
        var t = 0L
        var session = 1L
        outer@ for ((si, sentence) in stream.withIndex()) {
            if (si > 0 && si % SENTENCES_PER_SESSION == 0) {
                session++
                t += SESSION_GAP_MS
            }
            t += SENTENCE_GAP_MS
            val committed = StringBuilder()
            val words = ArrayList<String>()
            for ((k, token) in sentence.tokens.withIndex()) {
                committed.append(token.gap)
                if (!token.evaluated || !typist.canType(token.text)) {
                    committed.append(token.text)
                    words.add(token.text)
                    continue
                }
                if (wi >= limit) break@outer
                val before = tail(committed)
                val typed = typist.type(token.text, Random(EpisodeRunner.mix(seed, si, k)))
                val literal = typed.text
                val request =
                    PredictRequest(
                        TypingContext(before),
                        typed.input,
                        keyboard.layout,
                        STRIP,
                        autocorrectAllowed = true,
                    )
                current = request
                val result = engine.predictNow(request)
                val strip = result.candidates.take(STRIP).map { it.text }
                val autocorrect = result.autocorrect?.text
                val runnerUp = result.autocorrectRunnerUp?.text
                val situation = Situation(token.text, literal, strip, autocorrect, runnerUp)
                val reaction =
                    reactions.react(
                        situation,
                        Random(EpisodeRunner.mix(seed xor REACTION_SALT, si, k)),
                    )

                // ---- learning -------------------------------------------------------------
                if (learning) {
                    val strips = ArrayList<List<String>>(literal.length)
                    for (i in 1 until literal.length) {
                        val prefix =
                            ComposingInput(
                                literal.substring(0, i),
                                typed.input.touches.subList(0, i),
                            )
                        val r =
                            engine.predictNow(
                                PredictRequest(
                                    TypingContext(before),
                                    prefix,
                                    keyboard.layout,
                                    STRIP,
                                    autocorrectAllowed = false,
                                )
                            )
                        strips.add(r.candidates.take(STRIP).map { it.text })
                    }
                    strips.add(strip)
                    val record =
                        WordRecord(
                            timeMillis = t + literal.length * typistConfig.tapIntervalMs,
                            sessionId = session,
                            context = words.takeLast(2).map { it.replace('’', '\'') },
                            sentenceInitial = k == 0,
                            typed = typed,
                            target = token.text,
                            strips = strips,
                            autocorrect = autocorrect,
                            runnerUp = runnerUp,
                            reaction = reaction,
                        )
                    val events = builder.build(record)
                    for (e in events) model.apply(classifier.classify(e), e.timeMillis)
                    val adds = model.pendingUserDictionaryAdds()
                    if (adds.isNotEmpty()) {
                        userList = userList + adds.map { it.word }
                        for (a in adds) userKeys.add(PersonalUser.fold(a.word))
                    }
                    model.publishNow(events.last().timeMillis)
                }

                // ---- metrics --------------------------------------------------------------
                val personal = user.isPersonal(token.text)
                val fired = situation.fired
                val keyboardOut = if (fired) autocorrect!! else literal
                val literalRight = situation.literalRight
                val keyboardRight = keyboardOut.equals(token.text, ignoreCase = true)
                val finalRight = reaction.finalText.equals(token.text, ignoreCase = true)
                val falseCorrection = literalRight && fired && !keyboardRight
                val key = PersonalUser.fold(token.text)
                targets.add(key)
                val w = windows[windows.indexOfFirst { wi < it.to }]
                val isLast = w === windows.last()
                val undone =
                    reaction is Reaction.Revert ||
                        reaction is Reaction.Switch ||
                        reaction is Reaction.EditLater
                w.words++
                if (finalRight) w.finalCorrect++
                if (keyboardRight) w.keyboardCorrect++
                if (literalRight) w.literalCorrect++
                if (falseCorrection) w.falseCorrections++
                if (undone) w.reverts++
                if (reaction is Reaction.Revert) w.correctWordReverts++
                if (reaction is Reaction.Tap) w.taps++
                if (reaction is Reaction.Retype) w.retypes++
                val inStrip = literalRight || situation.targetRank >= 0
                if (inStrip) w.top3++
                if (personal) {
                    w.personalWords++
                    if (finalRight) w.personalFinalCorrect++
                    if (keyboardRight) w.personalKeyboardCorrect++
                    if (literalRight) w.personalLiteralCorrect++
                    if (falseCorrection) w.personalFalseCorrections++
                    if (undone) w.personalReverts++
                    if (inStrip) w.personalTop3++
                    for (tally in
                        listOfNotNull(
                            perWord.getOrPut(key) { WordTally() },
                            if (isLast) perWordLast.getOrPut(key) { WordTally() } else null,
                        )) {
                        tally.occurrences++
                        if (literalRight) tally.literalRight++
                        if (keyboardRight) tally.keyboardRight++
                        if (finalRight) tally.finalRight++
                        if (falseCorrection) tally.falseCorrections++
                        if (undone) tally.reverts++
                    }
                    if (falseCorrection && isLast && examples.size < MAX_EXAMPLES) {
                        examples.add("${token.text}: typed '$literal' -> '$autocorrect' (word $wi)")
                    }
                }
                // Note-2 rule: a literal whose autocorrection was reverted before must not be
                // corrected again.
                if (literalRight && PersonalUser.fold(literal) in reverted) {
                    w.afterRevertOccurrences++
                    if (fired) {
                        w.afterRevertCorrected++
                        if (personal) w.personalAfterRevertCorrected++
                    }
                }
                if (
                    reaction is Reaction.Revert &&
                        (reaction.kind == RevertKind.ImmediateBackspace ||
                            reaction.kind == RevertKind.DeletedAndRetyped)
                ) {
                    reverted.add(PersonalUser.fold(literal))
                }
                if (typed.typo == TypoKind.None && token.text.length == literal.length) {
                    val samples = builder.touches(typed)
                    for (i in literal.indices) {
                        val sample = samples[i] ?: continue
                        val meant = keyboard.key(token.text[i]) ?: continue
                        val p = typed.input.touches[i]!!
                        oracle.add(
                            meant.char.lowercaseChar(),
                            (p.x - meant.centerX) / meant.width,
                            (p.y - meant.centerY) / meant.height,
                        )
                        if (sample.char == meant.char.lowercaseChar()) {
                            conf.add(sample.char, sample.dx, sample.dy)
                        }
                    }
                }

                if (wi < traceWords) {
                    trace.add(
                        listOf(
                                wi,
                                token.text,
                                literal,
                                strip.joinToString("|"),
                                autocorrect.orEmpty(),
                                runnerUp.orEmpty(),
                                reaction::class.simpleName,
                                reaction.finalText,
                            )
                            .joinToString(",")
                    )
                }
                committed.append(reaction.finalText)
                words.add(reaction.finalText)
                t += literal.length * typistConfig.tapIntervalMs + WORD_GAP_MS
                wi++
                if (wi == w.to) {
                    touchMetrics(w, conf, oracle)
                    log("[$name] ${w.label} words done")
                }
            }
        }
        val breakdown = if (learning) userWordBreakdown(targets) else null
        return ArmResult(
            name,
            learning,
            windows.filter { it.words > 0 },
            perWord,
            perWordLast,
            examples,
            breakdown,
            if (learning) model.suppressedCorrections().size else 0,
            (System.nanoTime() - started) / 1e9,
            trace,
        )
    }

    private fun touchMetrics(w: LearnWindow, confirmed: TouchSums, oracle: TouchSums) {
        val bx = typistConfig.biasX.toDouble()
        val by = typistConfig.biasY.toDouble()
        if (learning) {
            val snap = model.snapshot()
            var keys = 0
            var dx = 0.0
            var dy = 0.0
            var err = 0.0
            for (c in 'a'..'z') {
                val (ox, oy) = snap.touchOffset(profile.touchKey, c) ?: continue
                keys++
                dx += ox
                dy += oy
                err += hypot(ox - bx, oy - by)
            }
            w.learnedKeys = keys
            if (keys > 0) {
                w.learnedMeanDx = dx / keys
                w.learnedMeanDy = dy / keys
                w.learnedError = err / keys
            }
        }
        confirmed.error(bx, by, MIN_EMPIRICAL)?.let { (keys, e) ->
            w.attainableKeys = keys
            w.attainableError = e
        }
        oracle.error(bx, by, MIN_EMPIRICAL)?.let { (_, e) -> w.oracleError = e }
    }

    /** Per-key sums of touch offsets. */
    private class TouchSums {
        private val n = HashMap<Char, Int>()
        private val x = HashMap<Char, Double>()
        private val y = HashMap<Char, Double>()

        fun add(c: Char, dx: Float, dy: Float) {
            n.merge(c, 1, Int::plus)
            x.merge(c, dx.toDouble(), Double::plus)
            y.merge(c, dy.toDouble(), Double::plus)
        }

        /**
         * (keys, mean L2 distance of the per-key mean to (bx, by)) over keys with >= [min] taps.
         */
        fun error(bx: Double, by: Double, min: Int): Pair<Int, Double>? {
            var keys = 0
            var err = 0.0
            for ((c, cnt) in n) {
                if (cnt < min) continue
                keys++
                err += hypot(x.getValue(c) / cnt - bx, y.getValue(c) / cnt - by)
            }
            return if (keys == 0) null else keys to err / keys
        }
    }

    private fun userWordBreakdown(targets: Set<String>): UserWordBreakdown {
        val words = model.userWords().map { it.word }
        val keys = words.map { PersonalUser.fold(it) }.toSet()
        var personal = 0
        var other = 0
        val pollution = ArrayList<String>()
        for (w in words) {
            val k = PersonalUser.fold(w)
            when {
                user.isPersonal(w) -> personal++
                k in targets -> other++
                else -> pollution.add(w)
            }
        }
        val missing =
            user.words.filter { PersonalUser.fold(it) in targets && PersonalUser.fold(it) !in keys }
        return UserWordBreakdown(words.size, personal, other, pollution, missing)
    }

    private fun tail(text: CharSequence): String =
        if (text.length <= TypingContext.MAX_BEFORE_CHARS) text.toString()
        else text.substring(text.length - TypingContext.MAX_BEFORE_CHARS)

    companion object {
        const val STRIP = 3
        const val SENTENCES_PER_SESSION = 20
        const val SESSION_GAP_MS = 45 * 60_000L
        const val SENTENCE_GAP_MS = 3_000L
        const val WORD_GAP_MS = 700L
        const val MAX_EXAMPLES = 25
        const val MIN_EMPIRICAL = 30
        private const val REACTION_SALT = 0x1F3D5B79L

        /** Window ends at 10%, 25%, 50% and 100% of [words] (2k/5k/10k/20k for 20 000). */
        fun windowEnds(words: Int): List<Int> =
            listOf(0.10, 0.25, 0.50, 1.0).map { (it * words).toInt() }.distinct().filter { it > 0 }
    }
}

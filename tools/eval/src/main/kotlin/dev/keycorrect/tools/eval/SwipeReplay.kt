// Report formatting and the path resampler use plain numeric constants (percent, point counts).
@file:Suppress("MagicNumber")

package dev.keycorrect.tools.eval

import dev.keycorrect.core.decoder.ClassicConfig
import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.engineapi.GestureInput
import dev.keycorrect.core.engineapi.GestureRequest
import dev.keycorrect.core.engineapi.KeySpec
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.WordCommitted
import dev.keycorrect.core.learning.classifier.ClassifierConfig
import dev.keycorrect.core.learning.classifier.OutcomeClassifier
import dev.keycorrect.core.learning.model.PersonalModel
import dev.keycorrect.core.learning.model.PersonalModelConfig
import dev.keycorrect.core.swipenet.SwipeEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/** One swipe.futo.org session as one simulated user: its swipes in time order, split in halves. */
class SwipeSession(val id: String, val swipes: List<RealSwipe>) {
    /** The first half (rounded down) of the session: the user's history before scoring starts. */
    val early: List<RealSwipe> = swipes.subList(0, swipes.size / 2)

    /** The second half: scored. */
    val late: List<RealSwipe> = swipes.subList(swipes.size / 2, swipes.size)
}

/** Grouping of recorded swipes into simulated users. */
object SwipeSessions {
    /**
     * Groups [swipes] by session; each session is ordered by (timestamp, id) and kept when it has
     * at least [minSwipes] swipes; rows without a session id are left out (they can't be one user).
     * Sessions come back ordered by id, so the result does not depend on the file's row order
     * beyond equal (timestamp, id) pairs, which keep file order.
     */
    fun group(swipes: List<RealSwipe>, minSwipes: Int): List<SwipeSession> =
        swipes
            .filter { it.session.isNotEmpty() }
            .groupBy { it.session }
            .filter { it.value.size >= minSwipes }
            .toSortedMap()
            .map { (id, list) ->
                SwipeSession(id, list.sortedWith(compareBy<RealSwipe>({ it.timestamp }, { it.id })))
            }
}

/**
 * The oracle path calibration of the replay (docs/EVAL.md, "Swipe learning replay"): one affine map
 * per session, fitted so the session's gestures line up with the ideal paths (the polyline through
 * the key centres) of their target words. Diagnostic only: it uses the targets of all the session's
 * swipes, which a keyboard would not know.
 */
object PathCalibration {
    const val POINTS = 64

    /**
     * [points] resampled to [n] points evenly spaced by arc length; null for a zero-length path.
     */
    fun resample(points: List<DoubleArray>, n: Int = POINTS): List<DoubleArray>? {
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] =
                cumulative[i - 1] +
                    hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1])
        }
        val total = cumulative.last()
        if (total <= 1e-9) return null
        val out = ArrayList<DoubleArray>(n)
        var seg = 1
        for (k in 0 until n) {
            val d = total * k / (n - 1)
            while (seg < points.size - 1 && cumulative[seg] < d) seg++
            val span = cumulative[seg] - cumulative[seg - 1]
            val f =
                if (span <= 1e-12) 0.0 else ((d - cumulative[seg - 1]) / span).coerceIn(0.0, 1.0)
            out +=
                doubleArrayOf(
                    points[seg - 1][0] + f * (points[seg][0] - points[seg - 1][0]),
                    points[seg - 1][1] + f * (points[seg][1] - points[seg - 1][1]),
                )
        }
        return out
    }

    /** The centres of the keys of [word]'s letters, in order (apostrophes have no key). */
    fun idealPath(word: String, keyboard: EvalKeyboard): List<DoubleArray> = word.mapNotNull { c ->
        if (c.isLetter()) {
            keyboard.key(c)?.let { doubleArrayOf(it.centerX.toDouble(), it.centerY.toDouble()) }
        } else {
            null
        }
    }

    /**
     * The least-squares affine map taking the [swipes]' gestures to their words' ideal paths (both
     * resampled to [POINTS] points by arc length), or null when there are too few usable pairs.
     */
    fun fit(swipes: List<RealSwipe>, keyboard: EvalKeyboard): AffineMap? {
        val (src, dst) = pairs(swipes, keyboard)
        if (src.size < 3) return null
        return AffineMap.fit(src, dst, keyWidth(keyboard))
    }

    /**
     * The best pure translation for the same pairs (the mean difference): a diagnostic next to
     * [fit], whose scale terms are biased towards 0 by the arc-length alignment's noise.
     */
    fun fitOffset(swipes: List<RealSwipe>, keyboard: EvalKeyboard): AffineMap? {
        val (src, dst) = pairs(swipes, keyboard)
        if (src.isEmpty()) return null
        val dx = src.indices.sumOf { dst[it][0] - src[it][0] } / src.size
        val dy = src.indices.sumOf { dst[it][1] - src[it][1] } / src.size
        return AffineMap(doubleArrayOf(1.0, 0.0, dx), doubleArrayOf(0.0, 1.0, dy), 0.0)
    }

    private fun pairs(
        swipes: List<RealSwipe>,
        keyboard: EvalKeyboard,
    ): Pair<List<DoubleArray>, List<DoubleArray>> {
        val src = ArrayList<DoubleArray>()
        val dst = ArrayList<DoubleArray>()
        for (s in swipes) {
            val gesture =
                resample(s.gesture.points.map { doubleArrayOf(it.x.toDouble(), it.y.toDouble()) })
            val ideal = resample(idealPath(s.target, keyboard))
            if (gesture == null || ideal == null) continue
            src += gesture
            dst += ideal
        }
        return src to dst
    }

    fun apply(map: AffineMap, gesture: GestureInput): GestureInput =
        GestureInput(
            gesture.points.map {
                TouchPoint(
                    map.mapX(it.x.toDouble(), it.y.toDouble()),
                    map.mapY(it.x.toDouble(), it.y.toDouble()),
                    it.timeMillis,
                )
            }
        )

    fun keyWidth(keyboard: EvalKeyboard): Double =
        keyboard.layout.keys.map { it.width.toDouble() }.average()

    /** How far a fitted map moves the keyboard, in key widths. */
    class Shape(
        /** Mean displacement of the letter key centres. */
        val offsetX: Double,
        val offsetY: Double,
        /** The diagonal of the linear part (1 = no scaling). */
        val scaleX: Double,
        val scaleY: Double,
        /** The off-diagonal of the linear part (0 = no skew). */
        val skewXY: Double,
        val skewYX: Double,
    )

    fun shape(map: AffineMap, keyboard: EvalKeyboard): Shape {
        val kw = keyWidth(keyboard)
        val keys = keyboard.layout.keys.filter { it.char.isLetter() }
        val c = map.coefficients()
        fun dx(k: KeySpec) = map.mapX(k.centerX.toDouble(), k.centerY.toDouble()) - k.centerX

        fun dy(k: KeySpec) = map.mapY(k.centerX.toDouble(), k.centerY.toDouble()) - k.centerY
        return Shape(
            keys.map(::dx).average() / kw,
            keys.map(::dy).average() / kw,
            c[0],
            c[4],
            c[1],
            c[3],
        )
    }
}

/**
 * One simulated user for the word-use learning arm: a fresh [ClassicEngine] bound to a fresh
 * [PersonalModel] exactly as the app wires it (LearnArm in LearnSim.kt does the same for typed
 * words): the engine reads the model's snapshot per decode and the user words the model adds;
 * events go through [OutcomeClassifier] into the model, which publishes after every swipe.
 */
class SwipeLearner(
    private val dictionary: Dictionary,
    config: ClassicConfig,
    encoder: () -> SwipeEncoder?,
    seed: Long,
    modelConfig: PersonalModelConfig = PersonalModelConfig(),
    classifierConfig: ClassifierConfig = ClassifierConfig(),
) {
    private val model = PersonalModel(modelConfig)
    private val classifier = OutcomeClassifier(classifierConfig)
    private var userList: List<String> = emptyList()
    private val userKeys = HashSet<String>()
    private var clock = 0L

    val engine =
        ClassicEngine(
            dictionary,
            config,
            userWords = { userList },
            personalization = { model.snapshot() },
            touchKey = { EventBuilder.DEFAULT_PROFILE.touchKey },
            random = Random(seed),
            swipeEncoder = encoder,
        )

    private fun isKnown(word: String): Boolean {
        val node = dictionary.findNode(PersonalUser.fold(word))
        return (node >= 0 && dictionary.isTerminal(node)) || PersonalUser.fold(word) in userKeys
    }

    /**
     * The simulated user's reaction to one swipe whose strip held [top] (the engine's top three):
     *
     * * the top word is the target: kept. The app's commitSwipe reports it as
     *   [CommitMethod.Gesture] with no typed text and no touches.
     * * otherwise the app has still committed the top word (a Gesture event for it); then
     *     - the target is on the strip: the user taps it (replaceSwipedWord), reported as a second
     *       Gesture commit of the target 800 ms later;
     *     - or the user fixes it some other way: a Typed commit of the target, no touches.
     *
     * Deviations from the app: it also emits WordReplaced for a strip replacement and feeds the
     * sentence buffer (new-word promotion from sentences); neither is replayed. Time is the swipe's
     * recording time, kept increasing.
     */
    fun learn(swipe: RealSwipe, top: List<String>) {
        val target = swipe.target
        val t = maxOf(clock + 1, swipe.timestamp)
        clock = t
        val context = context(swipe)
        val first = top.firstOrNull()
        val events = ArrayList<WordCommitted>(2)
        // An empty decode commits nothing in the app (the strip is restored); the user types it.
        if (first != null) events += commit(t, context, swipe, first, CommitMethod.Gesture)
        if (first == null || PersonalUser.fold(first) != PersonalUser.fold(target)) {
            val onStrip = top.any { PersonalUser.fold(it) == PersonalUser.fold(target) }
            val method = if (onStrip) CommitMethod.Gesture else CommitMethod.Typed
            clock = t + CORRECTION_MS
            events += commit(clock, context, swipe, target, method)
        }
        for (e in events) model.apply(classifier.classify(e), e.timeMillis)
        val adds = model.pendingUserDictionaryAdds()
        if (adds.isNotEmpty()) {
            userList = userList + adds.map { it.word }
            for (a in adds) userKeys.add(PersonalUser.fold(a.word))
        }
        model.publishNow(events.last().timeMillis)
    }

    private fun commit(
        time: Long,
        context: List<String>,
        swipe: RealSwipe,
        word: String,
        method: CommitMethod,
    ) =
        WordCommitted(
            timeMillis = time,
            profile = EventBuilder.DEFAULT_PROFILE,
            context = context,
            typed = if (method == CommitMethod.Gesture) "" else word,
            committed = word,
            method = method,
            sentenceInitial = swipe.context.isBlank(),
            committedIsKnown = isKnown(word),
            typedIsKnown = isKnown(word),
            sessionId = 1L,
        )

    private fun context(swipe: RealSwipe): List<String> =
        swipe.context
            .split(' ')
            .filter { it.isNotEmpty() }
            .takeLast(2)
            .map {
                it.replace('’', '\'')
            }

    private companion object {
        const val CORRECTION_MS = 800L
    }
}

/** What one decode of a late-half swipe came to. */
class SwipeOutcome(val top1: Boolean, val top3: Boolean, val none: Boolean)

/** Counters of an arm over the scored swipes (the [SwipeResult] definitions). */
class SwipeTally(val words: Int, val top1: Int, val top3: Int, val none: Int) {
    val wrongCommits: Int
        get() = words - top1 - none

    fun pct(n: Int) = if (words == 0) 0.0 else 100.0 * n / words

    companion object {
        fun of(outcomes: List<SwipeOutcome>) =
            SwipeTally(
                outcomes.size,
                outcomes.count { it.top1 },
                outcomes.count { it.top3 },
                outcomes.count { it.none },
            )
    }
}

/** Per-swipe outcomes of one arm over every kept session's late half, in a fixed order. */
class ArmOutcomes(
    val name: String,
    val outcomes: List<SwipeOutcome>,
    val dictionary: List<Boolean>,
) {
    fun tally(onlyDictionary: Boolean) =
        SwipeTally.of(outcomes.filterIndexed { i, _ -> !onlyDictionary || dictionary[i] })

    /** Swipes top-1 right in [base] and wrong here. */
    fun regressions(base: ArmOutcomes, onlyDictionary: Boolean) =
        count(base, onlyDictionary) { b, a -> b.top1 && !a.top1 }

    /** Swipes wrong in [base] and top-1 right here. */
    fun fixes(base: ArmOutcomes, onlyDictionary: Boolean) =
        count(base, onlyDictionary) { b, a -> !b.top1 && a.top1 }

    private fun count(
        base: ArmOutcomes,
        onlyDictionary: Boolean,
        pick: (SwipeOutcome, SwipeOutcome) -> Boolean,
    ): Int =
        outcomes.indices.count {
            (!onlyDictionary || dictionary[it]) && pick(base.outcomes[it], outcomes[it])
        }
}

/** The calibration fits across sessions. */
class CalibrationSummary(val shapes: List<PathCalibration.Shape>) {
    private fun mean(f: (PathCalibration.Shape) -> Double) = shapes.map(f).average()

    private fun meanAbs(f: (PathCalibration.Shape) -> Double) = shapes.map { abs(f(it)) }.average()

    fun text(): String =
        String.format(
            Locale.ROOT,
            "sessions fitted %d\n" +
                "offset in key widths, mean over sessions  x %+.3f  y %+.3f" +
                "  (mean abs x %.3f  y %.3f)\n" +
                "scale (1 = none)                          x %.3f  y %.3f\n" +
                "skew (0 = none)                           xy %+.3f  yx %+.3f\n",
            shapes.size,
            mean { it.offsetX },
            mean { it.offsetY },
            meanAbs { it.offsetX },
            meanAbs { it.offsetY },
            mean { it.scaleX },
            mean { it.scaleY },
            mean { it.skewXY },
            mean { it.skewYX },
        )

    fun toMap(): Map<String, Any> =
        linkedMapOf(
            "sessionsFitted" to shapes.size,
            "meanOffsetX" to mean { it.offsetX },
            "meanOffsetY" to mean { it.offsetY },
            "meanAbsOffsetX" to meanAbs { it.offsetX },
            "meanAbsOffsetY" to meanAbs { it.offsetY },
            "meanScaleX" to mean { it.scaleX },
            "meanScaleY" to mean { it.scaleY },
            "meanSkewXY" to mean { it.skewXY },
            "meanSkewYX" to mean { it.skewYX },
        )
}

/**
 * The ceiling of a swipe misdecode memory keyed by the word pair (decoded -> picked), counted on
 * the baseline's decodes: in each session, in time order, a wrong top-1 whose target is on the
 * strip (top 3) records the pair. A late-half swipe is [fixable] at k when its wrong top-1 and its
 * target (in the top 8) form a pair recorded at least k times, and [atRisk] at k when its right
 * top-1 forms such a pair with another top-8 candidate (a memory strong enough to fix the first
 * could flip the second).
 */
class MisdecodeCeiling {
    var scored = 0
    var wrong = 0
    val fixable = IntArray(MAX_K)
    val atRisk = IntArray(MAX_K)

    /** Counts one session's swipes, given [top8] (folded) per swipe in session order. */
    fun add(session: SwipeSession, top8: List<List<String>>) {
        val pairs = HashMap<String, Int>()
        for ((i, s) in session.swipes.withIndex()) {
            val top = top8[i]
            if (top.isEmpty()) continue
            val target = PersonalUser.fold(s.target)
            val first = top[0]
            if (i >= session.early.size) count(pairs, top, target)
            if (first != target && target in top.take(STRIP))
                pairs.merge(key(first, target), 1, Int::plus)
        }
    }

    private fun count(pairs: Map<String, Int>, top: List<String>, target: String) {
        val first = top[0]
        scored++
        if (first != target) wrong++
        for (k in 1..MAX_K) {
            if (first != target && target in top && (pairs[key(first, target)] ?: 0) >= k) {
                fixable[k - 1]++
            }
            if (first == target && top.drop(1).any { (pairs[key(first, it)] ?: 0) >= k }) {
                atRisk[k - 1]++
            }
        }
    }

    private fun key(a: String, b: String) = "$a>$b"

    fun text(): String = buildString {
        append(String.format(Locale.ROOT, "late-half swipes %d, wrong top-1 %d\n", scored, wrong))
        for (k in 1..MAX_K) {
            append(
                String.format(
                    Locale.ROOT,
                    "  pair seen >= %d times: fixable %d, correct swipes at risk %d\n",
                    k,
                    fixable[k - 1],
                    atRisk[k - 1],
                )
            )
        }
    }

    fun toMap(): Map<String, Any> =
        linkedMapOf(
            "scored" to scored,
            "wrongTop1" to wrong,
            "fixable" to fixable.toList(),
            "atRisk" to atRisk.toList(),
        )

    companion object {
        const val MAX_K = 3
        const val TOP = 8
        private const val STRIP = 3
    }
}

/** Everything the replay measured. */
class ReplayResult(
    val sessions: Int,
    val sessionsSeen: Int,
    val early: Int,
    val arms: List<ArmOutcomes>,
    val calibration: CalibrationSummary,
    val misdecodes: MisdecodeCeiling,
) {
    private val base: ArmOutcomes
        get() = arms.first()

    val scored: Int
        get() = base.outcomes.size

    val scoredDictionary: Int
        get() = base.dictionary.count { it }

    fun text(): String = buildString {
        for (arm in arms) {
            append("== " + arm.name + " ==\n")
            append(line("all", arm, false))
            append(line("dictionary words", arm, true))
            append("\n")
        }
        append("== path calibration fits ==\n")
        append(calibration.text())
        append("\n== misdecode memory ceiling (baseline decodes, top 8) ==\n")
        append(misdecodes.text())
    }

    private fun line(label: String, arm: ArmOutcomes, dict: Boolean): String {
        val t = arm.tally(dict)
        val b = base.tally(dict)
        val head =
            String.format(
                Locale.ROOT,
                "late half, %-16s n %6d  top-1 %6.2f %%  top-3 %6.2f %%  wrong commits %6.2f %%\n",
                label,
                t.words,
                t.pct(t.top1),
                t.pct(t.top3),
                t.pct(t.wrongCommits),
            )
        if (arm === base) return head
        return head +
            String.format(
                Locale.ROOT,
                "    vs baseline: top-1 %+.2f  top-3 %+.2f  wrong commits %+.2f points;" +
                    "  regressions %d  fixes %d\n",
                t.pct(t.top1) - b.pct(b.top1),
                t.pct(t.top3) - b.pct(b.top3),
                t.pct(t.wrongCommits) - b.pct(b.wrongCommits),
                arm.regressions(base, dict),
                arm.fixes(base, dict),
            )
    }

    fun toMap(): Map<String, Any> {
        val armMaps = LinkedHashMap<String, Any>()
        for (arm in arms) {
            val m = LinkedHashMap<String, Any>()
            m["all"] = armMap(arm, false)
            m["dictionary"] = armMap(arm, true)
            armMaps[arm.name] = m
        }
        return linkedMapOf(
            "sessionsKept" to sessions,
            "sessionsSeen" to sessionsSeen,
            "earlySwipes" to early,
            "scoredSwipes" to scored,
            "scoredDictionarySwipes" to scoredDictionary,
            "arms" to armMaps,
            "calibration" to calibration.toMap(),
            "misdecodeCeiling" to misdecodes.toMap(),
        )
    }

    private fun armMap(arm: ArmOutcomes, dict: Boolean): Map<String, Any> {
        val t = arm.tally(dict)
        val e =
            linkedMapOf<String, Any>(
                "words" to t.words,
                "top1" to t.pct(t.top1) / 100,
                "top3" to t.pct(t.top3) / 100,
                "wrongCommits" to t.pct(t.wrongCommits) / 100,
                "noCandidate" to t.pct(t.none) / 100,
            )
        if (arm !== base) {
            val b = base.tally(dict)
            e["top1Delta"] = (t.pct(t.top1) - b.pct(b.top1)) / 100
            e["top3Delta"] = (t.pct(t.top3) - b.pct(b.top3)) / 100
            e["wrongCommitsDelta"] = (t.pct(t.wrongCommits) - b.pct(b.wrongCommits)) / 100
            e["regressions"] = arm.regressions(base, dict)
            e["fixes"] = arm.fixes(base, dict)
        }
        return e
    }
}

/**
 * The swipe-learning replay (docs/PHASE10.md, 10a-5): every session of a real-swipe file is one
 * user; the engine learns over the session's first half and is scored on its second. Arms: no
 * learning, today's word-use learning, and the oracle path calibration. Single-threaded, with a
 * fresh engine per session for the learning arm, so the output is deterministic.
 */
class SwipeReplay(
    private val keyboard: EvalKeyboard,
    private val evaluator: SwipeEvaluator,
    private val baseline: ClassicEngine,
    private val newLearner: () -> SwipeLearner,
    private val maxCandidates: Int = 3,
) {
    fun run(swipes: List<RealSwipe>, minSwipes: Int): ReplayResult {
        val sessions = SwipeSessions.group(swipes, minSwipes)
        val base = ArrayList<SwipeOutcome>()
        val word = ArrayList<SwipeOutcome>()
        val calibrated = ArrayList<SwipeOutcome>()
        val shifted = ArrayList<SwipeOutcome>()
        val dictionary = ArrayList<Boolean>()
        val shapes = ArrayList<PathCalibration.Shape>()
        val misdecodes = MisdecodeCeiling()
        for (session in sessions) {
            countMisdecodes(misdecodes, session)
            val map = PathCalibration.fit(session.swipes, keyboard)
            val shift = PathCalibration.fitOffset(session.swipes, keyboard)
            map?.let { shapes += PathCalibration.shape(it, keyboard) }
            val learner = newLearner()
            for (s in session.early) learner.learn(s, top(learner.engine, evaluator.request(s)))
            for (s in session.late) {
                val request = evaluator.request(s)
                val top = top(learner.engine, request)
                word += outcome(s.target, top)
                learner.learn(s, top)
                base += outcome(s.target, top(baseline, request))
                val moved =
                    if (map == null) request
                    else request.copy(gesture = PathCalibration.apply(map, s.gesture))
                calibrated += outcome(s.target, top(baseline, moved))
                val translated =
                    if (shift == null) request
                    else request.copy(gesture = PathCalibration.apply(shift, s.gesture))
                shifted += outcome(s.target, top(baseline, translated))
                dictionary += s.inDictionary
            }
        }
        return ReplayResult(
            sessions.size,
            swipes.map { it.session }.toSet().size,
            sessions.sumOf { it.early.size },
            listOf(
                ArmOutcomes("baseline (no learning)", base, dictionary),
                ArmOutcomes("word-use learning (production)", word, dictionary),
                ArmOutcomes(
                    "path calibration ceiling (oracle, diagnostic)",
                    calibrated,
                    dictionary,
                ),
                ArmOutcomes(
                    "offset-only calibration ceiling (oracle, diagnostic)",
                    shifted,
                    dictionary,
                ),
            ),
            CalibrationSummary(shapes),
            misdecodes,
        )
    }

    private fun countMisdecodes(ceiling: MisdecodeCeiling, session: SwipeSession) {
        val top8 =
            session.swipes.map { s ->
                top(baseline, evaluator.request(s), MisdecodeCeiling.TOP).map(PersonalUser::fold)
            }
        ceiling.add(session, top8)
    }

    private fun top(
        engine: ClassicEngine,
        request: GestureRequest,
        n: Int = maxCandidates,
    ): List<String> = engine.decodeGestureNow(request).candidates.take(n).map { it.text }

    private fun outcome(target: String, top: List<String>): SwipeOutcome {
        val t = PersonalUser.fold(target)
        return SwipeOutcome(
            top.isNotEmpty() && PersonalUser.fold(top[0]) == t,
            top.any { PersonalUser.fold(it) == t },
            top.isEmpty(),
        )
    }
}

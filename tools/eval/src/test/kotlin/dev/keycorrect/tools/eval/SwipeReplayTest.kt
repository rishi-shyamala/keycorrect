package dev.keycorrect.tools.eval

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.GestureInput
import dev.keycorrect.core.engineapi.TouchPoint
import org.junit.Test

class SwipeReplayTest {
    private val keyboard = EvalKeyboard()

    private fun swipe(session: String, ts: Long, id: Long, word: String = "the") =
        RealSwipe(
            word,
            "",
            GestureInput(listOf(TouchPoint(0f, 0f, 0L), TouchPoint(1f, 1f, 1L))),
            true,
            session,
            ts,
            id,
        )

    @Test
    fun sessionsAreGroupedFilteredAndOrderedByTimeThenId() {
        val swipes =
            listOf(
                swipe("b", 30, 3),
                swipe("a", 5, 9),
                swipe("b", 10, 2),
                swipe("b", 10, 1),
            )
        val sessions = SwipeSessions.group(swipes, minSwipes = 2)
        assertThat(sessions.map { it.id }).containsExactly("b")
        assertThat(sessions[0].swipes.map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun theFirstHalfRoundedDownIsEarlyAndTheRestLate() {
        val s = SwipeSessions.group(List(5) { swipe("s", it.toLong(), it.toLong()) }, 1).single()
        assertThat(s.early.map { it.id }).containsExactly(0L, 1L).inOrder()
        assertThat(s.late.map { it.id }).containsExactly(2L, 3L, 4L).inOrder()
    }

    @Test
    fun affineFitRecoversAKnownSimilarityOnSyntheticGestures() {
        // Gestures are the ideal paths under a known scale + rotation + shift; the fitted map must
        // take them back to the ideal paths.
        val words = listOf("hello", "world", "keyboard", "swipe", "typing")
        val c = 0.99
        val sn = 0.14
        fun skew(p: DoubleArray) =
            doubleArrayOf(1.1 * (c * p[0] - sn * p[1]) + 14.0, 1.1 * (sn * p[0] + c * p[1]) - 9.0)
        val swipes = words.map { w ->
            val ideal = PathCalibration.idealPath(w, keyboard)
            val dense =
                ideal.zipWithNext().flatMap { (a, b) ->
                    (0 until 20).map { i ->
                        doubleArrayOf(a[0] + (b[0] - a[0]) * i / 20, a[1] + (b[1] - a[1]) * i / 20)
                    }
                } + listOf(ideal.last())
            val g = dense.map(::skew)
            RealSwipe(
                w,
                "",
                GestureInput(
                    g.mapIndexed { i, p -> TouchPoint(p[0].toFloat(), p[1].toFloat(), i.toLong()) }
                ),
                true,
            )
        }
        val map = PathCalibration.fit(swipes, keyboard)!!
        val ideal = PathCalibration.idealPath("hello", keyboard)
        val first = swipes[0].gesture.points.first()
        assertThat(map.mapX(first.x.toDouble(), first.y.toDouble()).toDouble())
            .isWithin(0.5)
            .of(ideal.first()[0])
        assertThat(map.mapY(first.x.toDouble(), first.y.toDouble()).toDouble())
            .isWithin(0.5)
            .of(ideal.first()[1])
        val shape = PathCalibration.shape(map, keyboard)
        assertThat(shape.scaleX).isWithin(0.01).of(c / 1.1)
    }

    @Test
    fun theReportIsDeterministic() {
        fun outcomes(vararg hit: Boolean) = hit.map { SwipeOutcome(it, it, false) }
        fun result() =
            ReplayResult(
                1,
                2,
                3,
                listOf(
                    ArmOutcomes("baseline", outcomes(true, false, true), listOf(true, true, false)),
                    ArmOutcomes("arm", outcomes(true, true, false), listOf(true, true, false)),
                ),
                CalibrationSummary(emptyList()),
                MisdecodeCeiling(),
            )
        val a = result()
        val b = result()
        assertThat(a.text()).isEqualTo(b.text())
        assertThat(Json.write(a.toMap())).isEqualTo(Json.write(b.toMap()))
        assertThat(a.text()).contains("regressions 1  fixes 1")
    }

    @Test
    fun theMisdecodeCeilingCountsRepeatedPairsAndCorrectSwipesAtRisk() {
        // Early half: "form" decoded for "from" twice (picked from the strip). Late half: the same
        // misdecode again (fixable), and a correct "form" with "from" behind it (at risk).
        val s =
            SwipeSessions.group(
                    listOf(
                        swipe("s", 1, 1, "from"),
                        swipe("s", 2, 2, "from"),
                        swipe("s", 3, 3, "from"),
                        swipe("s", 4, 4, "form"),
                    ),
                    1,
                )
                .single()
        val top = listOf("form", "from", "for")
        val c = MisdecodeCeiling()
        c.add(s, List(4) { top })
        assertThat(c.scored).isEqualTo(2)
        assertThat(c.wrong).isEqualTo(1)
        assertThat(c.fixable.toList()).containsExactly(1, 1, 0).inOrder()
        // By the last swipe the pair has been seen three times (the late misdecode counts too).
        assertThat(c.atRisk.toList()).containsExactly(1, 1, 1).inOrder()
    }

    @Test
    fun rowsWithoutASessionAreLeftOut() {
        val sessions = SwipeSessions.group(List(3) { swipe("", it.toLong(), it.toLong()) }, 1)
        assertThat(sessions).isEmpty()
    }
}

package dev.keycorrect.feature.keyboard

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeySlot
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.layout.KeyboardSet
import org.junit.Test

class TouchStateMachineTest {
    private class FakeHost : TouchHost {
        override var showPreviews = true
        override var longPressDelayMs = 300L
        override var spacebarCursor = true
        override var backspaceSwipe = true
        override var slopPx = 8f
        override var wordStepPx = 40f
        override var swipeTyping = true
        override var swipeTrail = true
        val swipeEvents = mutableListOf<String>()
        var swipePoints = emptyList<Triple<Float, Float, Long>>()
        val drags = mutableListOf<Triple<Int, Int, Boolean>>()
        val swipes = mutableListOf<Int>()
        val swipeEnds = mutableListOf<Boolean>()
        var ticks = 0
        override var clipboardLongPress = true
        val keyLongPresses = mutableListOf<KeyAction>()

        override fun onKeyLongPress(action: KeyAction) {
            keyLongPresses += action
            events += "keyLongPress"
        }

        override val gestures =
            object : KeyGestures {
                override fun onCursorDrag(dx: Int, dy: Int, select: Boolean) {
                    drags += Triple(dx, dy, select)
                }

                override fun onBackspaceSwipe(words: Int) {
                    swipes += words
                }

                override fun onBackspaceSwipeEnd(commit: Boolean) {
                    swipeEnds += commit
                }

                override fun onSwipeStart(path: SwipePath) {
                    swipeEvents += "start"
                    snapshot(path)
                }

                override fun onSwipeMove(path: SwipePath) {
                    swipeEvents += "move"
                    snapshot(path)
                }

                override fun onSwipeEnd(path: SwipePath) {
                    swipeEvents += "end"
                    snapshot(path)
                }

                override fun onSwipeCancel() {
                    swipeEvents += "cancel"
                }

                private fun snapshot(path: SwipePath) {
                    swipePoints = List(path.size) { Triple(path.x(it), path.y(it), path.time(it)) }
                }
            }

        override fun onGestureTick() {
            ticks++
        }

        val events = mutableListOf<String>()

        /** Actions passed to [onKey] only, in order. */
        val keys = mutableListOf<KeyAction>()

        /** The touch point delivered with each key, in order. */
        val touches = mutableListOf<TouchPoint?>()
        var popupOpened = 0

        /** Callbacks (by name, or "all") that throw, like a host whose input connection died. */
        val failing = mutableSetOf<String>()

        private fun maybeThrow(name: String) {
            if (name in failing || "all" in failing) throw IllegalStateException("dead: $name")
        }

        override fun onPress(action: KeyAction) {
            events += "press"
            maybeThrow("press")
        }

        override fun onPopupOpened() {
            popupOpened++
            events += "popup"
            maybeThrow("popup")
        }

        /** Runs when a key is delivered, to look at the state the key sees. */
        var onKeyProbe: (() -> Unit)? = null

        override fun onKey(action: KeyAction, touch: TouchPoint?) {
            onKeyProbe?.invoke()
            keys += action
            touches += touch
            events += "key"
            maybeThrow("key")
        }

        override fun onShiftDown() {
            events += "shiftDown"
            maybeThrow("shiftDown")
        }

        override fun onShiftUp() {
            events += "shiftUp"
            maybeThrow("shiftUp")
        }
    }

    private var now = 0L
    private val host = FakeHost()
    private val touch = KeyboardTouchState()
    private val layer =
        KeyboardComposer(BuiltInLayouts.repository())
            .compose(KeyboardSet.Default, KeyboardSet.LETTERS)
    private val geometry = KeyboardGeometry(layer, 1000f, 400f)
    private val machine = TouchStateMachine(geometry, touch, host) { now }

    private fun slot(label: String): KeySlot = geometry.slots.first { it.key.label == label }

    private fun slot(action: KeyAction): KeySlot = geometry.slots.first { it.key.action == action }

    private fun down(id: Long, slot: KeySlot) = machine.onDown(id, slot.centerX, slot.centerY)

    private fun advance(ms: Long) {
        now += ms
        machine.onTimer()
    }

    private val a
        get() = slot("a")

    private val backspace
        get() = slot(KeyAction.Backspace)

    @Test
    fun tapCommitsOnReleaseNotPress() {
        down(1, a)
        assertThat(host.keys).isEmpty()
        assertThat(touch.pressed).containsExactly(a.index)
        assertThat(touch.preview).isEqualTo(a)
        machine.onUp(1)
        assertThat(host.keys).containsExactly(a.key.action)
        assertThat(touch.pressed).isEmpty()
        assertThat(touch.preview).isNull()
    }

    @Test
    fun theKeyIsReleasedOnScreenBeforeTheTypingPipelineRuns() {
        var pressedAtKey: Set<Int>? = null
        var previewAtKey: KeySlot? = a
        host.onKeyProbe = {
            pressedAtKey = touch.pressed
            previewAtKey = touch.preview
        }
        down(1, a)
        machine.onUp(1)
        assertThat(host.keys).containsExactly(a.key.action)
        assertThat(pressedAtKey).isEmpty()
        assertThat(previewAtKey).isNull()
    }

    @Test
    fun aTapDeliversTheDownPositionAndTime() {
        now = 1_000
        machine.onDown(1, a.centerX - 3f, a.centerY + 2f)
        now = 1_080
        machine.onMove(1, a.centerX + 5f, a.centerY)
        machine.onUp(1)
        assertThat(host.touches).containsExactly(TouchPoint(a.centerX - 3f, a.centerY + 2f, 1_000))
    }

    @Test
    fun repeatsAndPopupEntriesDeliverNoTouch() {
        down(1, backspace)
        advance(REPEAT_START_DELAY_MS)
        machine.onUp(1)
        assertThat(host.touches).containsExactly(null, null)
        host.touches.clear()
        down(2, a)
        advance(host.longPressDelayMs)
        machine.onUp(2)
        assertThat(host.touches).containsExactly(null)
    }

    @Test
    fun pressPlaysFeedbackImmediately() {
        down(1, a)
        assertThat(host.events).containsExactly("press")
    }

    @Test
    fun noPreviewWhenDisabledOrForNonCharacterKeys() {
        host.showPreviews = false
        down(1, a)
        assertThat(touch.preview).isNull()
        machine.onUp(1)
        host.showPreviews = true
        down(1, backspace)
        assertThat(touch.preview).isNull()
    }

    @Test
    fun touchOutsideEveryKeyHitsTheNearestOne() {
        machine.onDown(1, -50f, -50f)
        assertThat(touch.pressed).hasSize(1)
        machine.onUp(1)
        assertThat(host.keys).hasSize(1)
    }

    @Test
    fun backspaceCommitsOnPressThenRepeatsAfterDelay() {
        down(1, backspace)
        assertThat(host.keys).containsExactly(KeyAction.Backspace)
        advance(REPEAT_START_DELAY_MS - 1)
        assertThat(host.keys).hasSize(1)
        advance(1)
        assertThat(host.keys).hasSize(2)
        advance(REPEAT_INTERVAL_MS)
        advance(REPEAT_INTERVAL_MS)
        assertThat(host.keys).hasSize(4)
        machine.onUp(1)
        assertThat(host.keys).hasSize(4) // release types nothing more
        advance(10_000)
        assertThat(host.keys).hasSize(4)
        assertThat(machine.nextDeadline()).isNull()
    }

    @Test
    fun aStalledClockFiresOneRepeatNotABurst() {
        down(1, backspace)
        advance(REPEAT_START_DELAY_MS + 5_000)
        assertThat(host.keys).hasSize(2)
    }

    @Test
    fun repeatsFireBeforeALateEventIsHandled() {
        down(1, backspace)
        now += REPEAT_START_DELAY_MS
        machine.onUp(1) // no timer wake-up in between
        assertThat(host.keys).hasSize(2)
    }

    @Test
    fun longPressOpensPopupAfterDelay() {
        down(1, a)
        advance(299)
        assertThat(touch.popup).isNull()
        assertThat(touch.preview).isEqualTo(a)
        advance(1)
        val popup = touch.popup!!
        assertThat(popup.entries.map { it.label })
            .containsExactly("!", "à", "á", "â", "ä", "æ", "ã", "å", "ā") // the symbol hint first
            .inOrder()
        assertThat(popup.owner).isEqualTo(1L)
        assertThat(touch.preview).isNull()
        assertThat(host.popupOpened).isEqualTo(1)
        assertThat(popup.highlighted).isEqualTo(popup.panel.indexAt(a.centerX))
    }

    @Test
    fun releasingOverAPopupCellCommitsThatEntry() {
        down(1, a)
        advance(300)
        val popup = touch.popup!!
        val cell = 3
        machine.onMove(1, popup.panel.left + popup.panel.cellWidth * (cell + 0.5f), a.top - 10f)
        assertThat(popup.highlighted).isEqualTo(cell)
        machine.onUp(1)
        assertThat(host.keys).containsExactly(popup.entries[cell].action)
        assertThat(touch.popup).isNull()
        assertThat(touch.pressed).isEmpty()
    }

    @Test
    fun releasingBeforeTheDelayTypesTheKeyAndNeverOpensPopup() {
        down(1, a)
        advance(299)
        machine.onUp(1)
        assertThat(host.keys).containsExactly(a.key.action)
        advance(1_000)
        assertThat(touch.popup).isNull()
        assertThat(host.popupOpened).isEqualTo(0)
    }

    @Test
    fun movingFarBelowCancelsPopupAndReleaseTypesNothing() {
        down(1, a)
        advance(300)
        machine.onMove(1, a.centerX, a.bottom + (a.bottom - a.top) + 1f)
        assertThat(touch.popup).isNull()
        machine.onUp(1)
        assertThat(host.keys).isEmpty()
        assertThat(touch.pressed).isEmpty()
    }

    @Test
    fun movingJustBelowKeepsPopup() {
        down(1, a)
        advance(300)
        machine.onMove(1, a.centerX, a.bottom + 5f)
        assertThat(touch.popup).isNotNull()
    }

    @Test
    fun movingBeforePopupOpensSetsInitialHighlight() {
        down(1, a)
        machine.onMove(1, a.centerX + 2f, a.centerY)
        advance(300)
        val popup = touch.popup!!
        assertThat(popup.highlighted).isEqualTo(popup.panel.indexAt(a.centerX + 2f))
    }

    @Test
    fun secondFingerCancelsPopupAndFirstReleaseTypesNothing() {
        down(1, a)
        advance(300)
        assertThat(touch.popup).isNotNull()
        val s = slot("s")
        down(2, s)
        assertThat(touch.popup).isNull()
        machine.onUp(1)
        assertThat(host.keys).isEmpty()
        machine.onUp(2)
        assertThat(host.keys).containsExactly(slot("s").key.action)
        assertThat(touch.pressed).isEmpty()
    }

    @Test
    fun secondFingerBeforePopupLeavesFirstFingerTyping() {
        down(1, a)
        down(2, slot("d"))
        machine.onUp(1)
        machine.onUp(2)
        assertThat(host.keys).containsExactly(a.key.action, slot("d").key.action).inOrder()
    }

    @Test
    fun bothFingersShowAsPressed() {
        down(1, a)
        down(2, slot("d"))
        assertThat(touch.pressed).containsExactly(a.index, slot("d").index)
        machine.onUp(1)
        assertThat(touch.pressed).containsExactly(slot("d").index)
    }

    @Test
    fun shiftIsReportedOnPressAndReleaseAndNeverCommitted() {
        val shift = slot(KeyAction.Shift)
        down(1, shift)
        assertThat(host.events).containsExactly("press", "shiftDown").inOrder()
        advance(5_000)
        machine.onUp(1)
        assertThat(host.events).containsExactly("press", "shiftDown", "shiftUp").inOrder()
        assertThat(host.keys).isEmpty()
        assertThat(touch.pressed).isEmpty()
    }

    @Test
    fun shiftChordInterleavesKeysBetweenShiftDownAndUp() {
        val shift = slot(KeyAction.Shift)
        down(1, shift)
        down(2, a)
        machine.onUp(2)
        down(2, slot("s"))
        machine.onUp(2)
        machine.onUp(1)
        assertThat(host.events.filter { it != "press" })
            .containsExactly("shiftDown", "key", "key", "shiftUp")
            .inOrder()
        assertThat(host.keys).containsExactly(a.key.action, slot("s").key.action).inOrder()
    }

    @Test
    fun cancelClearsStateAndTypesNothing() {
        down(1, a)
        advance(300)
        machine.cancel(1)
        assertThat(touch.pressed).isEmpty()
        assertThat(touch.popup).isNull()
        assertThat(touch.preview).isNull()
        assertThat(host.keys).isEmpty()
        assertThat(machine.nextDeadline()).isNull()
    }

    @Test
    fun cancelStopsBackspaceRepeat() {
        down(1, backspace)
        machine.cancel(1)
        advance(10_000)
        assertThat(host.keys).hasSize(1)
        assertThat(machine.nextDeadline()).isNull()
    }

    @Test
    fun cancellingShiftReleasesIt() {
        down(1, slot(KeyAction.Shift))
        machine.cancel(1)
        assertThat(host.events).containsExactly("press", "shiftDown", "shiftUp").inOrder()
    }

    @Test
    fun cancelAllClearsEveryTouch() {
        down(1, backspace)
        down(2, slot(KeyAction.Shift))
        down(3, a)
        machine.cancelAll()
        assertThat(touch.pressed).isEmpty()
        assertThat(touch.preview).isNull()
        assertThat(machine.nextDeadline()).isNull()
        assertThat(host.events.last()).isEqualTo("shiftUp")
        machine.onUp(3)
        assertThat(host.keys).hasSize(1) // only the backspace press
    }

    @Test
    fun retainOnlyDropsVanishedFingers() {
        down(1, a)
        down(2, backspace)
        machine.retainOnly { it == 2L }
        assertThat(touch.pressed).containsExactly(backspace.index)
        machine.onUp(1)
        assertThat(host.keys).containsExactly(KeyAction.Backspace) // 'a' never typed
    }

    @Test
    fun unknownPointerEventsAreIgnored() {
        machine.onMove(9, 1f, 1f)
        machine.onUp(9)
        machine.cancel(9)
        assertThat(host.events).isEmpty()
    }

    @Test
    fun deadlineTracksTheEarliestPendingTimer() {
        assertThat(machine.nextDeadline()).isNull()
        now = 1_000
        down(1, a)
        assertThat(machine.nextDeadline()).isEqualTo(1_300L)
        down(2, backspace)
        assertThat(machine.nextDeadline()).isEqualTo(1_300L)
        machine.onUp(1)
        assertThat(machine.nextDeadline()).isEqualTo(1_000L + REPEAT_START_DELAY_MS)
    }

    // --- rolling chords, shift + popup ---

    @Test
    fun rollingChordWithShiftReleasedBeforeTheLetterStillTypesTheLetter() {
        val shift = slot(KeyAction.Shift)
        down(1, shift)
        down(2, a)
        machine.onUp(1) // shift first...
        assertThat(host.keys).isEmpty()
        machine.onUp(2) // ...then the letter
        assertThat(host.events.filter { it != "press" })
            .containsExactly("shiftDown", "shiftUp", "key")
            .inOrder()
        assertThat(host.keys).containsExactly(a.key.action)
        assertThat(touch.pressed).isEmpty()
    }

    @Test
    fun shiftHeldWhileALongPressPopupOpensAndCommits() {
        val shift = slot(KeyAction.Shift)
        down(1, shift)
        down(2, a)
        advance(300)
        val popup = touch.popup!!
        assertThat(popup.owner).isEqualTo(2L)
        assertThat(touch.pressed).containsExactly(shift.index, a.index)
        val cell = 2
        machine.onMove(2, popup.panel.left + popup.panel.cellWidth * (cell + 0.5f), a.top - 10f)
        machine.onUp(2)
        assertThat(host.keys).containsExactly(popup.entries[cell].action)
        assertThat(touch.popup).isNull()
        // Shift is still down: still pressed, and released on its own.
        assertThat(touch.pressed).containsExactly(shift.index)
        machine.onUp(1)
        assertThat(host.events.filter { it != "press" })
            .containsExactly("shiftDown", "popup", "key", "shiftUp")
            .inOrder()
    }

    @Test
    fun shiftAsSecondFingerDismissesAnOpenPopupLikeAnyKey() {
        down(1, a)
        advance(300)
        down(2, slot(KeyAction.Shift)) // a second finger dismisses the popup, as for any key
        assertThat(touch.popup).isNull()
        machine.onUp(1)
        assertThat(host.keys).isEmpty()
        machine.onUp(2)
        assertThat(host.events.last()).isEqualTo("shiftUp")
    }

    // --- failing host ---

    @Test
    fun hostThrowingOnKeyDoesNotBreakLaterTaps() {
        host.failing += "key"
        down(1, a)
        machine.onUp(1) // must not throw
        assertThat(touch.pressed).isEmpty()
        assertThat(touch.preview).isNull()
        host.failing.clear()
        down(1, slot("s"))
        machine.onUp(1)
        assertThat(host.keys).containsExactly(a.key.action, slot("s").key.action).inOrder()
        assertThat(touch.pressed).isEmpty()
        assertThat(machine.nextDeadline()).isNull()
    }

    @Test
    fun hostThrowingOnEveryCallbackLeavesConsistentTouchState() {
        host.failing += "all"
        down(1, a)
        assertThat(touch.pressed).containsExactly(a.index)
        advance(300) // popup opens although the host's callback throws
        assertThat(touch.popup).isNotNull()
        machine.onUp(1)
        assertThat(touch.popup).isNull()
        assertThat(touch.pressed).isEmpty()
        down(2, slot(KeyAction.Shift))
        machine.onUp(2)
        down(3, backspace)
        assertThat(machine.nextDeadline()).isNotNull() // repeat still armed despite the throw
        machine.cancel(3)
        assertThat(touch.pressed).isEmpty()
        host.failing.clear()
        down(1, a)
        machine.onUp(1)
        assertThat(host.keys.last()).isEqualTo(a.key.action)
    }

    @Test
    fun backspaceRepeatSurvivesAThrowingHost() {
        host.failing += "key"
        down(1, backspace)
        advance(REPEAT_START_DELAY_MS)
        advance(REPEAT_INTERVAL_MS)
        assertThat(host.keys).hasSize(3)
        machine.onUp(1)
        advance(10_000)
        assertThat(host.keys).hasSize(3)
    }

    @Test
    fun hostThrowingOnPressStillTracksAndCommitsTheKey() {
        host.failing += "press"
        down(1, a)
        assertThat(touch.pressed).containsExactly(a.index)
        machine.onUp(1)
        assertThat(host.keys).containsExactly(a.key.action)
    }

    @Test
    fun hostThrowingOnShiftStillReleasesShift() {
        host.failing += "shiftDown"
        down(1, slot(KeyAction.Shift))
        host.failing.clear()
        host.failing += "shiftUp"
        machine.onUp(1)
        assertThat(touch.pressed).isEmpty()
        assertThat(host.events.filter { it != "press" })
            .containsExactly("shiftDown", "shiftUp")
            .inOrder()
    }

    // --- Spacebar cursor and backspace swipe ---

    private val space
        get() = slot(KeyAction.Space)

    private fun move(id: Long, slot: KeySlot, dx: Float, dy: Float = 0f) =
        machine.onMove(id, slot.centerX + dx, slot.centerY + dy)

    /** A drag that is slow enough for no acceleration and never reaches the hold. */
    private fun slowSpace() {
        host.longPressDelayMs = 100_000L
        down(1, space)
        now += 1_000
    }

    @Test
    fun spaceTapStillTypesASpace() {
        down(1, space)
        move(1, space, 3f, 2f) // within the slop
        machine.onUp(1)
        assertThat(host.keys).containsExactly(KeyAction.Space)
        assertThat(host.drags).isEmpty()
    }

    // --- Spacebar joystick: pure rate control from the centre ---

    private val keyHeight
        get() = space.bottom - space.top

    private val ring
        get() = keyHeight * JOYSTICK_RING_KEY_HEIGHTS

    private val deadZone
        get() = ring * JOYSTICK_DEAD_ZONE_FRACTION

    /** Offset at which the rate has reached its maximum (far from every edge when sideways/up). */
    private val farOut
        get() = ring + keyHeight * JOYSTICK_FAST_KEY_HEIGHTS

    /** Total characters and lines reported so far. */
    private fun movedChars() = host.drags.sumOf { it.first }

    private fun movedLines() = host.drags.sumOf { it.second }

    /** Holds the finger still for [seconds] of timer ticks and returns the characters moved. */
    private fun charsOver(seconds: Int = 1): Int {
        val before = movedChars()
        repeat(seconds * 40) { advance(JOYSTICK_TICK_MS) }
        return movedChars() - before
    }

    private fun linesOver(seconds: Int = 1): Int {
        val before = movedLines()
        repeat(seconds * 40) { advance(JOYSTICK_TICK_MS) }
        return movedLines() - before
    }

    @Test
    fun theJoystickAppearsWhenTheDragStartsAndGoesOnRelease() {
        slowSpace()
        move(1, space, 4f) // within the slop
        assertThat(touch.joystick).isNull()
        move(1, space, 30f)
        val joystick = touch.joystick!!
        assertThat(joystick.centerX).isEqualTo(space.centerX)
        assertThat(joystick.centerY).isEqualTo(space.centerY)
        assertThat(joystick.radius).isWithin(0.01f).of(ring)
        assertThat(joystick.deadZone).isWithin(0.01f).of(ring * 0.25f)
        assertThat(joystick.knobX).isWithin(0.01f).of(space.centerX + 30f)
        machine.onUp(1)
        assertThat(touch.joystick).isNull()
    }

    @Test
    fun aLostTouchAndCancelAllClearTheJoystick() {
        slowSpace()
        move(1, space, 30f)
        machine.cancel(1)
        assertThat(touch.joystick).isNull()
        down(2, space)
        move(2, space, 30f)
        assertThat(touch.joystick).isNotNull()
        machine.cancelAll()
        assertThat(touch.joystick).isNull()
    }

    @Test
    fun theKnobStaysOnTheRing() {
        slowSpace()
        move(1, space, ring * 3f, 0f)
        val joystick = touch.joystick!!
        assertThat(joystick.knobX).isWithin(0.01f).of(space.centerX + ring)
        assertThat(joystick.knobY).isWithin(0.01f).of(space.centerY)
        assertThat(joystick.engaged).isTrue()
        move(1, space, 0f, -ring * 3f)
        assertThat(joystick.knobX).isWithin(0.01f).of(space.centerX)
        assertThat(joystick.knobY).isWithin(0.01f).of(space.centerY - ring)
    }

    @Test
    fun aDragThatStaysInTheDeadZoneMovesNothing() {
        slowSpace()
        move(1, space, host.slopPx + 2f) // past the slop, inside the dead zone
        assertThat(deadZone).isGreaterThan(host.slopPx + 2f)
        assertThat(touch.joystick).isNotNull()
        assertThat(machine.nextDeadline()).isNull()
        assertThat(charsOver(2)).isEqualTo(0)
        move(1, space, 0f, -deadZone * 0.9f)
        assertThat(linesOver(2)).isEqualTo(0)
        machine.onUp(1)
        assertThat(host.drags).isEmpty()
        assertThat(host.keys).isEmpty()
        assertThat(host.ticks).isEqualTo(0)
    }

    @Test
    fun justOutsideTheDeadZoneInchesAlongSlowly() {
        slowSpace()
        move(1, space, deadZone + 1f) // about 3 chars/s
        assertThat(machine.nextDeadline()).isEqualTo(now + JOYSTICK_TICK_MS)
        assertThat(host.drags).isEmpty()
        assertThat(charsOver()).isIn(2..4)
        assertThat(movedLines()).isEqualTo(0)
        assertThat(host.ticks).isEqualTo(0) // no haptic until the ring
    }

    @Test
    fun theRateEasesInFromTheDeadZoneToTheRingThenGrowsBeyondIt() {
        slowSpace()
        move(1, space, deadZone + 1f)
        val slow = charsOver()
        move(1, space, (deadZone + ring) / 2f) // halfway: 3 + 12 / 4 = 6 chars/s
        val half = charsOver()
        assertThat(half).isIn(5..7)
        move(1, space, ring - 1f) // about 15 chars/s
        val near = charsOver()
        assertThat(near).isIn(13..16)
        move(1, space, ring + keyHeight * 0.75f) // halfway to full: about 27 chars/s
        val past = charsOver()
        assertThat(past).isIn(25..29)
        move(1, space, farOut)
        val fast = charsOver()
        assertThat(fast).isIn(39..41)
        assertThat(listOf(slow, half, near, past, fast)).isInStrictOrder()
        move(1, space, farOut + 5 * keyHeight) // no faster than the maximum
        assertThat(charsOver()).isIn(39..41)
    }

    @Test
    fun heldStillTheCursorKeepsMovingOnTimerTicks() {
        slowSpace()
        move(1, space, farOut) // 40 chars/s
        assertThat(touch.joystick!!.engaged).isTrue()
        assertThat(machine.nextDeadline()).isEqualTo(now + JOYSTICK_TICK_MS)
        assertThat(host.drags).isEmpty()
        repeat(10) { advance(JOYSTICK_TICK_MS) } // 250 ms, finger still
        assertThat(movedChars()).isIn(9..10)
        assertThat(movedLines()).isEqualTo(0)
        assertThat(host.drags.all { !it.third }).isTrue()
        assertThat(machine.nextDeadline()).isNotNull()
        assertThat(charsOver()).isIn(39..41) // and on, as long as it is held
    }

    @Test
    fun beyondTheRingToTheLeftMovesLeft() {
        slowSpace()
        move(1, space, -farOut)
        assertThat(charsOver()).isIn(-41..-39)
    }

    @Test
    fun onlyTheLargerOffsetMoves() {
        slowSpace()
        move(1, space, farOut, -(deadZone + 5f)) // mostly sideways
        repeat(40) { advance(JOYSTICK_TICK_MS) }
        assertThat(movedChars()).isIn(39..41)
        assertThat(movedLines()).isEqualTo(0)
        host.drags.clear()
        move(1, space, deadZone + 5f, -farOut) // mostly up: 12 lines/s
        repeat(40) { advance(JOYSTICK_TICK_MS) }
        assertThat(movedLines()).isIn(-12..-11)
        assertThat(movedChars()).isEqualTo(0)
    }

    @Test
    fun verticalRatesAreSlowerThanHorizontalOnes() {
        slowSpace()
        move(1, space, 0f, -(deadZone + 1f)) // about 1 line/s
        assertThat(linesOver(2)).isIn(-3..-1)
        move(1, space, 0f, -(ring - 1f)) // about 4 lines/s
        assertThat(linesOver()).isIn(-4..-3)
    }

    @Test
    fun theHapticTickComesOnceWhenTheFingerCrossesTheRing() {
        slowSpace()
        move(1, space, ring - 2f)
        assertThat(host.ticks).isEqualTo(0)
        move(1, space, ring + 2f)
        assertThat(host.ticks).isEqualTo(1)
        move(1, space, farOut)
        assertThat(host.ticks).isEqualTo(1)
        move(1, space, ring - 2f)
        assertThat(touch.joystick!!.engaged).isFalse()
        move(1, space, ring + 2f)
        assertThat(host.ticks).isEqualTo(2)
    }

    @Test
    fun theSelectFlagIsKeptWhileMoving() {
        host.longPressDelayMs = 100_000L
        downLeft(1)
        now += 1_000
        moveLeft(1, farOut)
        repeat(10) { advance(JOYSTICK_TICK_MS) }
        assertThat(host.drags).isNotEmpty()
        assertThat(host.drags.all { it.third }).isTrue()
        moveLeft(1, -farOut)
        host.drags.clear()
        repeat(10) { advance(JOYSTICK_TICK_MS) }
        assertThat(host.drags).isNotEmpty()
        assertThat(host.drags.all { it.third }).isTrue()
    }

    @Test
    fun aStalledTimerDoesNotBurst() {
        slowSpace()
        move(1, space, farOut)
        advance(5_000) // main thread stalled
        assertThat(movedChars()).isAtMost(4) // 100 ms at most: 40 chars/s
    }

    @Test
    fun fingerPinnedAtTheBottomEdgeMovesDownAtFullSpeed() {
        slowSpace()
        // The space bar is on the bottom row: the surface's edge is half a key below its centre.
        val room = geometry.height - space.centerY
        assertThat(room).isLessThan(ring)
        move(1, space, 0f, room) // at the edge: a small offset, but full deflection
        assertThat(linesOver()).isIn(11..12)
        move(1, space, 0f, room + 3 * keyHeight) // below the surface (nav bar): the same
        assertThat(linesOver()).isIn(11..12)
    }

    @Test
    fun theRingShortensTowardsTheBottomEdgeSoTheWholeRangeFitsInTheRoomLeft() {
        slowSpace()
        val room = geometry.height - space.centerY
        val shortRing = room - keyHeight * JOYSTICK_EDGE_MARGIN_KEY_HEIGHTS
        move(1, space, 0f, shortRing - 1f) // at the (shortened) ring: medium, 4 lines/s
        assertThat(linesOver()).isIn(3..4)
        move(1, space, 0f, (shortRing + room) / 2f) // between ring and edge: between 4 and 12
        assertThat(linesOver()).isIn(7..9)
        // Upwards there is plenty of room: the same offset is still slow.
        move(1, space, 0f, -shortRing)
        assertThat(linesOver(2)).isIn(-3..-1)
        // With its centre low, a slow dead zone still exists.
        move(1, space, 0f, keyHeight * 0.1f)
        assertThat(linesOver()).isEqualTo(0)
    }

    @Test
    fun fingersPinnedAgainstAnyEdgeAreAtFullDeflection() {
        val kh = 100f
        val corner =
            JoystickState(20f, 30f, kh, 1000f, 400f) // 20 px from the left, 30 from the top
        assertThat(corner.level(0f, 30f)).isEqualTo(2f) // at the left edge
        assertThat(corner.level(-50f, 30f)).isEqualTo(2f) // beyond it
        assertThat(corner.level(20f, 0f)).isEqualTo(2f) // top
        // Away from the edges the ring is whole again: slow just outside the dead zone.
        assertThat(corner.level(20f + corner.deadZone + 1f, 30f)).isLessThan(0.1f)
        assertThat(corner.level(20f + corner.radius + 1f, 30f)).isGreaterThan(1f)
        assertThat(corner.level(20f + corner.radius + 1f, 30f)).isLessThan(1.1f)
        val right = JoystickState(980f, 200f, kh, 1000f, 400f)
        assertThat(right.level(1000f, 200f)).isEqualTo(2f)
        assertThat(right.level(980f + 5f, 200f)).isEqualTo(-1f) // dead zone first
    }

    @Test
    fun backToTheCentreStopsTheCursor() {
        slowSpace()
        move(1, space, farOut)
        repeat(4) { advance(JOYSTICK_TICK_MS) }
        val before = movedChars()
        assertThat(before).isGreaterThan(0)
        move(1, space, 3f) // back in the dead zone: no jump, nothing more
        assertThat(touch.joystick!!.engaged).isFalse()
        assertThat(machine.nextDeadline()).isNull()
        assertThat(charsOver(2)).isEqualTo(0)
        assertThat(movedChars()).isEqualTo(before)
    }

    @Test
    fun leavingTheDeadZoneAgainResumesTheRate() {
        slowSpace()
        move(1, space, farOut)
        move(1, space, 0f)
        assertThat(machine.nextDeadline()).isNull()
        move(1, space, farOut)
        assertThat(charsOver()).isIn(39..41)
    }

    @Test
    fun releasingStopsTheMovementClearsTheOverlayAndTypesNoSpace() {
        slowSpace()
        move(1, space, farOut)
        repeat(4) { advance(JOYSTICK_TICK_MS) }
        machine.onUp(1)
        val after = host.drags.size
        assertThat(touch.joystick).isNull()
        assertThat(machine.nextDeadline()).isNull()
        advance(1_000)
        assertThat(host.drags).hasSize(after)
        assertThat(host.keys).isEmpty()
    }

    @Test
    fun spaceHeldThenReleasedTypesASpace() {
        down(1, space)
        assertThat(machine.nextDeadline()).isNull()
        advance(1_000)
        move(1, space, 4f) // within the slop
        machine.onUp(1)
        assertThat(host.ticks).isEqualTo(0)
        assertThat(host.keys).containsExactly(KeyAction.Space)
        assertThat(host.drags).isEmpty()
    }

    /** A press in the middle of the left (select) half of the space bar. */
    private fun downLeft(id: Long) =
        machine.onDown(id, space.left + (space.right - space.left) / 4f, space.centerY)

    private fun moveLeft(id: Long, dx: Float) =
        machine.onMove(id, space.left + (space.right - space.left) / 4f + dx, space.centerY)

    @Test
    fun dragOnTheLeftHalfSelects() {
        host.longPressDelayMs = 100_000L
        downLeft(1)
        now += 1_000
        moveLeft(1, farOut)
        repeat(10) { advance(JOYSTICK_TICK_MS) }
        machine.onUp(1)
        assertThat(host.drags).isNotEmpty()
        assertThat(host.drags.all { it.third }).isTrue()
        assertThat(host.keys).isEmpty()
    }

    @Test
    fun dragOnTheRightHalfMovesEvenAfterTheHold() {
        down(1, space) // the centre is the right half's start
        advance(300)
        now += 1_000
        move(1, space, farOut)
        repeat(10) { advance(JOYSTICK_TICK_MS) }
        machine.onUp(1)
        assertThat(host.drags).isNotEmpty()
        assertThat(host.drags.all { !it.third }).isTrue()
        assertThat(host.keys).isEmpty()
    }

    @Test
    fun heldOnTheLeftHalfThenDraggedSelects() {
        downLeft(1)
        advance(300)
        now += 1_000
        moveLeft(1, farOut)
        repeat(10) { advance(JOYSTICK_TICK_MS) }
        machine.onUp(1)
        assertThat(host.drags).isNotEmpty()
        assertThat(host.drags.all { it.third }).isTrue()
    }

    @Test
    fun draggingBeforeTheHoldNeverArmsIt() {
        down(1, space)
        now += 10
        move(1, space, 30f)
        advance(1_000)
        machine.onUp(1)
        assertThat(host.drags.all { !it.third }).isTrue()
        assertThat(machine.nextDeadline()).isNull()
    }

    @Test
    fun withTheSpacebarCursorOffSpaceBehavesAsBefore() {
        host.spacebarCursor = false
        down(1, space)
        assertThat(machine.nextDeadline()).isNull()
        advance(1_000)
        move(1, space, 200f, 100f)
        machine.onUp(1)
        assertThat(host.keys).containsExactly(KeyAction.Space)
        assertThat(host.drags).isEmpty()
        assertThat(host.ticks).isEqualTo(0)
    }

    @Test
    fun cancellingASpaceDragOrHoldCommitsNothing() {
        slowSpace()
        move(1, space, 30f)
        machine.cancel(1)
        down(2, space)
        advance(300)
        machine.cancel(2)
        assertThat(host.keys).isEmpty()
        assertThat(host.swipeEnds).isEmpty()
    }

    @Test
    fun anotherFingerTypingDuringASpaceDragLeavesTheDragAlone() {
        slowSpace()
        move(1, space, farOut)
        repeat(4) { advance(JOYSTICK_TICK_MS) }
        down(2, a)
        machine.onUp(2)
        val before = movedChars()
        repeat(4) { advance(JOYSTICK_TICK_MS) }
        machine.onUp(1)
        assertThat(host.keys).containsExactly(a.key.action)
        assertThat(before).isGreaterThan(0)
        assertThat(movedChars()).isGreaterThan(before)
    }

    @Test
    fun backspaceStillDeletesOnPress() {
        down(1, backspace)
        assertThat(host.keys).containsExactly(KeyAction.Backspace)
        machine.onUp(1)
        assertThat(host.swipeEnds).isEmpty()
    }

    @Test
    fun backspaceSwipeCountsWordsUpAndDown() {
        down(1, backspace)
        move(1, backspace, -5f) // within the slop
        assertThat(host.swipes).isEmpty()
        move(1, backspace, -20f) // 12 past the slop
        move(1, backspace, -53f) // 45 past: 1 + 45 / 40
        move(1, backspace, -100f) // 92 past: 1 + 2
        move(1, backspace, -10f) // 2 past: back to 1
        move(1, backspace, -4f) // back within the slop: 0
        assertThat(host.swipes).containsExactly(1, 2, 3, 1, 0).inOrder()
        assertThat(host.ticks).isEqualTo(5)
        machine.onUp(1)
        assertThat(host.swipeEnds).containsExactly(false)
        assertThat(host.keys).containsExactly(KeyAction.Backspace)
    }

    @Test
    fun backspaceSwipeCommitsOnReleaseWithWordsSelected() {
        down(1, backspace)
        move(1, backspace, -60f)
        machine.onUp(1)
        assertThat(host.swipeEnds).containsExactly(true)
    }

    @Test
    fun aBackspaceSwipeStopsTheRepeat() {
        down(1, backspace)
        move(1, backspace, -30f)
        assertThat(machine.nextDeadline()).isNull()
        advance(2_000)
        assertThat(host.keys).hasSize(1)
        machine.onUp(1)
        assertThat(host.keys).hasSize(1)
    }

    @Test
    fun backspaceCannotSwipeOnceItIsRepeating() {
        down(1, backspace)
        advance(REPEAT_START_DELAY_MS)
        move(1, backspace, -60f)
        advance(REPEAT_INTERVAL_MS)
        machine.onUp(1)
        assertThat(host.swipes).isEmpty()
        assertThat(host.swipeEnds).isEmpty()
        assertThat(host.keys).hasSize(3)
    }

    @Test
    fun cancellingABackspaceSwipeEndsItWithoutCommitting() {
        down(1, backspace)
        move(1, backspace, -60f)
        machine.cancel(1)
        down(2, backspace)
        move(2, backspace, -60f)
        machine.retainOnly { false }
        assertThat(host.swipeEnds).containsExactly(false, false)
    }

    @Test
    fun withTheBackspaceSwipeOffBackspaceBehavesAsBefore() {
        host.backspaceSwipe = false
        down(1, backspace)
        move(1, backspace, -100f)
        advance(REPEAT_START_DELAY_MS)
        machine.onUp(1)
        assertThat(host.keys).containsExactly(KeyAction.Backspace, KeyAction.Backspace)
        assertThat(host.swipes).isEmpty()
        assertThat(host.swipeEnds).isEmpty()
    }

    @Test
    fun anotherFingerTypingDuringABackspaceSwipeLeavesTheSwipeAlone() {
        down(1, backspace)
        move(1, backspace, -30f)
        down(2, a)
        machine.onUp(2)
        move(1, backspace, -60f)
        machine.onUp(1)
        assertThat(host.keys).containsExactly(KeyAction.Backspace, a.key.action).inOrder()
        assertThat(host.swipes).containsExactly(1, 2).inOrder()
        assertThat(host.swipeEnds).containsExactly(true)
    }

    private val emoji
        get() = slot(KeyAction.Emoji)

    @Test
    fun holdingTheEmojiKeyFiresTheLongPressOnceAndDoesNotTap() {
        down(1, emoji)
        advance(299)
        assertThat(host.keyLongPresses).isEmpty()
        advance(1)
        assertThat(host.keyLongPresses).containsExactly(KeyAction.Emoji)
        assertThat(host.ticks).isEqualTo(1)
        assertThat(touch.preview).isNull()
        advance(1_000)
        machine.onUp(1)
        assertThat(host.keys).isEmpty()
        assertThat(host.keyLongPresses).hasSize(1)
        assertThat(touch.pressed).isEmpty()
    }

    @Test
    fun aShortTapOnTheEmojiKeyStillTaps() {
        down(1, emoji)
        advance(299)
        machine.onUp(1)
        assertThat(host.keys).containsExactly(KeyAction.Emoji)
        assertThat(host.keyLongPresses).isEmpty()
        assertThat(machine.nextDeadline()).isNull()
    }

    @Test
    fun theEmojiKeyIsAPlainKeyWhenTheClipboardIsOff() {
        host.clipboardLongPress = false
        down(1, emoji)
        assertThat(machine.nextDeadline()).isNull()
        advance(5_000)
        machine.onUp(1)
        assertThat(host.keys).containsExactly(KeyAction.Emoji)
        assertThat(host.keyLongPresses).isEmpty()
    }

    @Test
    fun aLostEmojiTouchFiresNothing() {
        down(1, emoji)
        machine.cancel(1)
        advance(1_000)
        assertThat(host.keyLongPresses).isEmpty()
        assertThat(host.keys).isEmpty()
    }

    @Test
    fun otherKeysHaveNoKeyLongPress() {
        down(1, a)
        advance(1_000)
        machine.onUp(1)
        assertThat(host.keyLongPresses).isEmpty()
    }

    // Gesture typing.

    private val s
        get() = slot("s")

    private val d
        get() = slot("d")

    private fun swipeFromAToD() {
        down(1, a)
        now += 10
        machine.onMove(1, s.centerX, s.centerY)
        now += 10
        machine.onMove(1, d.centerX, d.centerY)
    }

    @Test
    fun aSlowDragWithinOneKeyIsNotASwipe() {
        down(1, a)
        advance(100)
        machine.onMove(1, a.centerX + 20f, a.centerY + 10f)
        machine.onUp(1)
        assertThat(host.swipeEvents).isEmpty()
        assertThat(host.keys).containsExactly(a.key.action)
    }

    @Test
    fun travelUnderTheThresholdOnAnotherKeyIsNotASwipe() {
        down(1, a)
        // Over the next key already, but only about half a key width from the down point.
        val reach = (a.right - a.left) * SWIPE_START_KEY_WIDTHS
        machine.onMove(1, a.centerX + reach - 1f, a.centerY)
        assertThat(host.swipeEvents).isEmpty()
        machine.onUp(1)
        assertThat(host.keys).containsExactly(a.key.action)
    }

    @Test
    fun crossingToAnotherKeyPastTheThresholdStartsASwipe() {
        swipeFromAToD()
        assertThat(host.swipeEvents).containsExactly("start", "move").inOrder()
        assertThat(touch.preview).isNull()
        assertThat(touch.pressed).isEmpty()
        assertThat(touch.trail).isNotNull()
        now += 10
        machine.onMove(1, d.centerX + 5f, d.centerY)
        machine.onUp(1)
        assertThat(host.swipeEvents).containsExactly("start", "move", "move", "end").inOrder()
        assertThat(host.keys).isEmpty()
        assertThat(host.swipePoints.first()).isEqualTo(Triple(a.centerX, a.centerY, 0L))
        assertThat(host.swipePoints).hasSize(4)
        assertThat(host.swipePoints.last().first).isEqualTo(d.centerX + 5f)
        assertThat(touch.trail).isNull()
    }

    @Test
    fun theTrailIsNotPublishedWhenItIsOff() {
        host.swipeTrail = false
        swipeFromAToD()
        assertThat(touch.trail).isNull()
        assertThat(host.swipeEvents).contains("start")
    }

    @Test
    fun theLongPressPopupDoesNotOpenOnceSwiping() {
        val withPopup = geometry.slots.first { it.key.popup.isNotEmpty() && it.key.label == "e" }
        val next = geometry.slots.first { it.key.label == "r" }
        down(1, withPopup)
        machine.onMove(1, next.centerX, next.centerY)
        assertThat(host.swipeEvents).contains("start")
        assertThat(machine.nextDeadline()).isNull()
        advance(1_000)
        assertThat(touch.popup).isNull()
        assertThat(host.popupOpened).isEqualTo(0)
    }

    @Test
    fun anOpenPopupIsNotTurnedIntoASwipe() {
        val withPopup = geometry.slots.first { it.key.popup.isNotEmpty() && it.key.label == "e" }
        val next = geometry.slots.first { it.key.label == "r" }
        down(1, withPopup)
        advance(400)
        assertThat(touch.popup).isNotNull()
        machine.onMove(1, next.centerX, next.centerY)
        assertThat(host.swipeEvents).isEmpty()
    }

    @Test
    fun withSwipeTypingOffNothingChanges() {
        host.swipeTyping = false
        swipeFromAToD()
        machine.onUp(1)
        assertThat(host.swipeEvents).isEmpty()
        assertThat(host.keys).containsExactly(a.key.action)
    }

    @Test
    fun spaceBackspaceAndShiftNeverSwipe() {
        for (start in listOf(slot(KeyAction.Space), backspace, slot(KeyAction.Shift))) {
            host.swipeEvents.clear()
            down(1, start)
            machine.onMove(1, a.centerX, a.centerY)
            machine.onMove(1, d.centerX, d.centerY)
            machine.onUp(1)
            assertThat(host.swipeEvents).isEmpty()
        }
    }

    @Test
    fun aLostTouchCancelsTheSwipe() {
        swipeFromAToD()
        machine.cancel(1)
        assertThat(host.swipeEvents.last()).isEqualTo("cancel")
        assertThat(host.keys).isEmpty()
        assertThat(touch.trail).isNull()
    }

    @Test
    fun cancelAllCancelsTheSwipe() {
        swipeFromAToD()
        machine.cancelAll()
        assertThat(host.swipeEvents.last()).isEqualTo("cancel")
    }

    @Test
    fun aSecondFingerDuringASwipeIsIgnored() {
        swipeFromAToD()
        val before = host.events.toList()
        down(2, slot("q"))
        machine.onUp(2)
        assertThat(host.events).isEqualTo(before)
        assertThat(host.keys).isEmpty()
        machine.onMove(1, d.centerX + 3f, d.centerY)
        machine.onUp(1)
        assertThat(host.swipeEvents.last()).isEqualTo("end")
        assertThat(host.keys).isEmpty()
    }

    @Test
    fun thePathIsClearedAfterTheSwipeEnds() {
        swipeFromAToD()
        machine.onUp(1)
        host.swipeEvents.clear()
        // A second swipe starts again from just its own points.
        swipeFromAToD()
        assertThat(host.swipePoints).hasSize(3)
        machine.onUp(1)
        assertThat(host.swipeEvents).containsExactly("start", "move", "end").inOrder()
    }

    @Test
    fun theSwipePathHoldsThePointsFromBeforeRecognition() {
        down(1, a)
        now += 10
        machine.onMove(1, a.centerX + 10f, a.centerY + 1f)
        now += 10
        machine.onMove(1, a.centerX + 25f, a.centerY + 2f)
        assertThat(host.swipeEvents).isEmpty()
        now += 10
        machine.onMove(1, s.centerX, s.centerY)
        machine.onUp(1)
        assertThat(host.swipeEvents).containsExactly("start", "end").inOrder()
        assertThat(host.swipePoints.map { it.first })
            .containsExactly(a.centerX, a.centerX + 10f, a.centerX + 25f, s.centerX)
            .inOrder()
        assertThat(host.swipePoints.map { it.third }).containsExactly(0L, 10L, 20L, 30L).inOrder()
    }

    @Test
    fun aTapDoesNotLeaveBufferedPointsForTheNextSwipe() {
        down(1, a)
        machine.onMove(1, a.centerX + 10f, a.centerY)
        machine.onUp(1)
        host.swipeEvents.clear()
        swipeFromAToD()
        assertThat(host.swipePoints).hasSize(3)
    }
}

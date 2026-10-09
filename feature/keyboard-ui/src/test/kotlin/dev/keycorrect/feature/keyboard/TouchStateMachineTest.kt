package dev.keycorrect.feature.keyboard

import com.google.common.truth.Truth.assertThat
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
        val events = mutableListOf<String>()

        /** Actions passed to [onKey] only, in order. */
        val keys = mutableListOf<KeyAction>()
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

        override fun onKey(action: KeyAction) {
            keys += action
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
            .containsExactly("à", "á", "â", "ä", "æ", "ã", "å", "ā")
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
}

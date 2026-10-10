package dev.keycorrect.feature.keyboard.prediction

import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.learning.DeviceClass
import dev.keycorrect.core.learning.InputProfile
import dev.keycorrect.core.learning.LearningEvent
import dev.keycorrect.core.learning.LearningSink
import dev.keycorrect.core.learning.ShownSuggestion

/**
 * The learning side of the keyboard's one privacy gate (docs/PLAN.md §6.3 row 17). Everything the
 * keyboard learns from goes through [emit] or [sentenceWord], and both do nothing unless [open]:
 * the field allows prediction and learning, learning is on in the settings and incognito is off
 * ([sentenceWord] additionally needs the sentence buffer switched on). The owner closes the gate
 * and drops all learning state ([PredictiveInput]) the moment any of these turns false.
 *
 * [clock] is a monotonic clock for durations (tests drive it); [wallClock] stamps events and seeds
 * session ids, so ids keep growing across process restarts.
 */
internal class LearningRecorder(
    private val sink: LearningSink,
    private val clock: () -> Long,
    private val wallClock: () -> Long,
) {
    /** True while events may be emitted. */
    var open = false
        private set

    private var sentenceOpen = false

    /** Id of the current session: a new one starts for each new field. */
    var sessionId = wallClock()
        private set

    private var device = DeviceClass.Phone
    private var landscape = false
    private var layoutKey = DEFAULT_LAYOUT_KEY
    private var fieldClass = FieldClass.Text
    private var cached: InputProfile? = null

    /** Opens or closes the gate. [sentenceBuffer] is the extra switch for sentence words. */
    fun configure(open: Boolean, sentenceBuffer: Boolean) {
        this.open = open
        sentenceOpen = open && sentenceBuffer
    }

    fun newSession() {
        sessionId++
    }

    fun setDevice(device: DeviceClass, landscape: Boolean) {
        if (this.device == device && this.landscape == landscape) return
        this.device = device
        this.landscape = landscape
        cached = null
    }

    fun setLayoutKey(key: String) {
        if (layoutKey == key) return
        layoutKey = key
        cached = null
    }

    fun setField(field: FieldClass) {
        if (fieldClass == field) return
        fieldClass = field
        cached = null
    }

    val profile: InputProfile
        get() =
            cached ?: InputProfile(device, landscape, layoutKey, fieldClass).also { cached = it }

    /** Monotonic time, for durations. */
    fun now(): Long = clock()

    /** Wall-clock time, for event stamps. */
    fun stamp(): Long = wallClock()

    fun emit(event: LearningEvent) {
        if (open) sink.onEvent(event)
    }

    fun sentenceWord(word: String, sentenceEnd: Boolean) {
        if (sentenceOpen) sink.onSentenceWord(sessionId, word, sentenceEnd, wallClock())
    }

    companion object {
        const val DEFAULT_LAYOUT_KEY = "qwerty@54dp"
    }
}

/**
 * The suggestions that were on the strip while a word was typed: for each text the slot it last sat
 * in, how many characters were typed when it first appeared and how long it was visible. Kept cheap
 * on purpose (a handful of entries updated once per keystroke).
 *
 * Only plain suggestion and autocorrect slots count: the literal is what the user typed, and
 * phrases and shortcuts are not words.
 */
internal class ShownTracker {
    private class Entry(
        var rank: Int,
        val charsWhenShown: Int,
        val firstAt: Long,
        var lastSeen: Long,
        var visible: Boolean,
    )

    private val entries = LinkedHashMap<String, Entry>()

    /** The strip changed (or was re-published): [chars] of the word were typed at [now]. */
    fun onStrip(model: StripModel, chars: Int, now: Long) {
        for (e in entries.values) {
            if (e.visible) e.lastSeen = now // visible until this strip replaced it
            e.visible = false
        }
        note(model.centre, 0, chars, now)
        note(model.left, 1, chars, now)
        note(model.right, 2, chars, now)
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.entries.minByOrNull { it.value.lastSeen } ?: break
            entries.remove(oldest.key)
        }
    }

    private fun note(slot: StripSlot?, rank: Int, chars: Int, now: Long) {
        if (slot == null || slot.text.isEmpty()) return
        if (slot.kind != SlotKind.Suggestion && slot.kind != SlotKind.Autocorrect) return
        val e = entries.getOrPut(slot.insert) { Entry(rank, chars, now, now, true) }
        e.rank = rank
        e.lastSeen = now
        e.visible = true
    }

    /** What was shown, the suggestions still visible first, then the most recently seen. */
    fun snapshot(now: Long): List<ShownSuggestion> =
        entries.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Entry>> { it.value.visible }
                    .thenByDescending { it.value.lastSeen }
                    .thenBy { it.value.rank }
            )
            .map { (text, e) ->
                val end = if (e.visible) now else e.lastSeen
                ShownSuggestion(text, e.rank, e.charsWhenShown, (end - e.firstAt).coerceAtLeast(0))
            }

    fun clear() = entries.clear()

    private companion object {
        const val MAX_ENTRIES = 8
    }
}

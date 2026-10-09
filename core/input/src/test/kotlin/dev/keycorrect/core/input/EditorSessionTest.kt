package dev.keycorrect.core.input

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import org.junit.Test

/**
 * [EditorSession] against a fake [InputConnection]. The fake is a dynamic proxy that implements
 * only the methods the session uses and records every call; anything else returns null/0/false.
 * [KeyEvent] is a stub in JVM tests (its getters return 0), so key events are recorded where the
 * session creates them.
 */
class EditorSessionTest {
    private class Fake {
        /** Text the editor reports before the cursor; null mimics editors that expose none. */
        var before: CharSequence? = ""
        var after: CharSequence? = ""
        var selected: CharSequence? = null
        var capsMode = 0

        /** Every call as "name(args)" in order. */
        val calls = mutableListOf<String>()
        val keys = mutableListOf<Pair<Int, Int>>()
        private val keyOfEvent = IdentityHashMap<KeyEvent, Pair<Int, Int>>()

        fun newKeyEvent(action: Int, keyCode: Int): KeyEvent {
            val event = KeyEvent(action, keyCode)
            keyOfEvent[event] = action to keyCode
            return event
        }

        val connection: InputConnection =
            Proxy.newProxyInstance(
                InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java),
            ) { _, method, args ->
                val a = args.orEmpty()
                calls += "${method.name}(${a.joinToString { it.toString() }})".replace("()", "")
                when (method.name) {
                    "getSelectedText" -> selected
                    "getTextBeforeCursor" -> before
                    "getTextAfterCursor" -> after
                    "getCursorCapsMode" -> capsMode
                    "sendKeyEvent" -> {
                        keys += keyOfEvent.getValue(a[0] as KeyEvent)
                        true
                    }
                    "commitText",
                    "setComposingText",
                    "finishComposingText",
                    "beginBatchEdit",
                    "endBatchEdit",
                    "deleteSurroundingText",
                    "performEditorAction" -> true
                    else -> null
                }
            } as InputConnection

        fun reads() = calls.filter { it.startsWith("get") }
    }

    private val fake = Fake()
    private var info: EditorInfo? = EditorInfo()
    private var connected = true

    private val session =
        EditorSession(
            connection = { if (connected) fake.connection else null },
            editorInfo = { info },
            newKeyEvent = fake::newKeyEvent,
        )

    private fun field(inputType: Int, imeOptions: Int = 0) {
        info =
            EditorInfo().apply {
                this.inputType = inputType
                this.imeOptions = imeOptions
            }
    }

    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP

    @Test
    fun commitTextForwardsWithCursorAfter() {
        session.commitText("hi")
        assertThat(fake.calls).containsExactly("commitText(hi, 1)")
    }

    @Test
    fun deleteWithSelectionCommitsEmptyText() {
        fake.selected = "abc"
        session.deleteBackward(sensitive = false)
        assertThat(fake.calls).containsExactly("getSelectedText(0)", "commitText(, 1)").inOrder()
    }

    @Test
    fun deleteRemovesOneCharacter() {
        fake.before = "hello"
        session.deleteBackward(sensitive = false)
        assertThat(fake.calls).contains("deleteSurroundingText(1, 0)")
    }

    @Test
    fun deleteRemovesWholeEmoji() {
        fake.before = "hi 😀"
        session.deleteBackward(sensitive = false)
        assertThat(fake.calls).contains("deleteSurroundingText(2, 0)")
    }

    @Test
    fun deleteRemovesWholeZwjSequence() {
        val family = "👨‍👩‍👧"
        fake.before = "a$family"
        session.deleteBackward(sensitive = false)
        assertThat(fake.calls).contains("deleteSurroundingText(${family.length}, 0)")
    }

    @Test
    fun deleteKeepsAccentWithBaseLetter() {
        fake.before = "café"
        session.deleteBackward(sensitive = false)
        assertThat(fake.calls).contains("deleteSurroundingText(2, 0)")
    }

    @Test
    fun deleteInEmptyFieldDoesNothing() {
        fake.before = ""
        session.deleteBackward(sensitive = false)
        assertThat(fake.calls.filterNot { it.startsWith("get") }).isEmpty()
        assertThat(fake.keys).isEmpty()
    }

    @Test
    fun deleteWithoutExposedTextSendsDelKey() {
        fake.before = null
        session.deleteBackward(sensitive = false)
        assertThat(fake.keys)
            .containsExactly(down to KeyEvent.KEYCODE_DEL, up to KeyEvent.KEYCODE_DEL)
            .inOrder()
        assertThat(fake.calls).doesNotContain("deleteSurroundingText")
    }

    @Test
    fun sensitiveDeleteNeverReadsText() {
        fake.before = "hunter2"
        fake.selected = "hunter2"
        session.deleteBackward(sensitive = true)
        assertThat(fake.reads()).isEmpty()
        assertThat(fake.keys)
            .containsExactly(down to KeyEvent.KEYCODE_DEL, up to KeyEvent.KEYCODE_DEL)
            .inOrder()
        assertThat(fake.calls.filter { it.startsWith("commitText") }).isEmpty()
    }

    @Test
    fun noConnectionIsANoOp() {
        connected = false
        session.commitText("x")
        session.deleteBackward(sensitive = false)
        session.deleteBackward(sensitive = true)
        session.performEnter()
        assertThat(session.cursorCapsMode()).isEqualTo(0)
        assertThat(fake.calls).isEmpty()
    }

    @Test
    fun enterPerformsTheImeAction() {
        field(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH)
        session.performEnter()
        assertThat(fake.calls)
            .containsExactly("performEditorAction(${EditorInfo.IME_ACTION_SEARCH})")
        assertThat(fake.keys).isEmpty()
    }

    @Test
    fun enterInMultiLineFieldCommitsNewline() {
        field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        session.performEnter()
        assertThat(fake.calls).containsExactly("commitText(\n, 1)")
        assertThat(fake.keys).isEmpty()
    }

    @Test
    fun enterWithNoEnterActionFlagInMultiLineFieldCommitsNewline() {
        field(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
        )
        session.performEnter()
        assertThat(fake.calls).containsExactly("commitText(\n, 1)")
    }

    @Test
    fun enterInSingleLineFieldWithoutActionSendsEnterKey() {
        field(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NONE)
        session.performEnter()
        assertThat(fake.keys)
            .containsExactly(down to KeyEvent.KEYCODE_ENTER, up to KeyEvent.KEYCODE_ENTER)
            .inOrder()
        assertThat(fake.calls.filter { it.startsWith("commitText") }).isEmpty()
    }

    @Test
    fun enterInTypeNullFieldSendsEnterKey() {
        field(InputType.TYPE_NULL, EditorInfo.IME_ACTION_SEND)
        session.performEnter()
        assertThat(fake.keys)
            .containsExactly(down to KeyEvent.KEYCODE_ENTER, up to KeyEvent.KEYCODE_ENTER)
            .inOrder()
        assertThat(fake.calls.filter { it.startsWith("performEditorAction") }).isEmpty()
    }

    @Test
    fun enterWithoutEditorInfoSendsEnterKey() {
        info = null
        session.performEnter()
        assertThat(fake.keys)
            .containsExactly(down to KeyEvent.KEYCODE_ENTER, up to KeyEvent.KEYCODE_ENTER)
            .inOrder()
    }

    @Test
    fun capsModeUsesTheFieldsInputType() {
        field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        fake.capsMode = 0x4000
        assertThat(session.cursorCapsMode()).isEqualTo(0x4000)
        assertThat(fake.calls.single()).startsWith("getCursorCapsMode(")
        info = null
        assertThat(session.cursorCapsMode()).isEqualTo(0)
    }

    // --- composing and context ---

    @Test
    fun composingCallsForwardWithCursorAfter() {
        session.setComposingText("hel")
        session.finishComposingText()
        session.commitText("hello", 1)
        session.commitText("tail", 0)
        session.deleteSurroundingText(3, 1)
        assertThat(fake.calls)
            .containsExactly(
                "setComposingText(hel, 1)",
                "finishComposingText",
                "commitText(hello, 1)",
                "commitText(tail, 0)",
                "deleteSurroundingText(3, 1)",
            )
            .inOrder()
    }

    @Test
    fun textAroundTheCursorIsReadWithoutFlags() {
        fake.before = "left"
        fake.after = "right"
        assertThat(session.textBeforeCursor(256).toString()).isEqualTo("left")
        assertThat(session.textAfterCursor(64).toString()).isEqualTo("right")
        assertThat(fake.calls)
            .containsExactly("getTextBeforeCursor(256, 0)", "getTextAfterCursor(64, 0)")
            .inOrder()
    }

    @Test
    fun sensitiveFieldsNeverExposeTheirText() {
        val sensitive =
            listOf(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            )
        fake.before = "hunter2"
        fake.after = "hunter2"
        for (inputType in sensitive) {
            field(inputType)
            assertThat(session.textBeforeCursor(256)).isNull()
            assertThat(session.textAfterCursor(64)).isNull()
        }
        assertThat(fake.reads()).isEmpty()
    }

    @Test
    fun readsAreRefusedWithoutEditorInfoAndNullWithoutConnection() {
        info = null
        assertThat(session.textBeforeCursor(10)).isNull()
        info = EditorInfo()
        connected = false
        assertThat(session.textBeforeCursor(10)).isNull()
        session.setComposingText("x")
        session.finishComposingText()
        session.deleteSurroundingText(1, 0)
        assertThat(fake.calls).isEmpty()
    }

    @Test
    fun batchEditBracketsTheBlockAndEndsEvenWhenItThrows() {
        val result = session.batchEdit {
            session.commitText("a")
            7
        }
        assertThat(result).isEqualTo(7)
        runCatching { session.batchEdit { error("boom") } }
        assertThat(fake.calls)
            .containsExactly(
                "beginBatchEdit",
                "commitText(a, 1)",
                "endBatchEdit",
                "beginBatchEdit",
                "endBatchEdit",
            )
            .inOrder()
    }

    @Test
    fun batchEditWithoutConnectionStillRunsTheBlock() {
        connected = false
        assertThat(session.batchEdit { 3 }).isEqualTo(3)
        assertThat(fake.calls).isEmpty()
    }
}

package dev.keycorrect.fieldlab

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "FieldLab"
private const val PAD_DP = 12
private const val TERMINAL_HEIGHT_DP = 96
private const val LOG_TEXT_SP = 12f
private const val SAMPLE_MIN_LINES = 5

private const val SAMPLE_PARAGRAPH =
    "The quick brown fox jumps over the lazy dog.\n" +
        "Pack my box with five dozen liquor jugs.\n" +
        "How vexingly quick daft zebras jump!\n" +
        "Sphinx of black quartz, judge my vow."

/** Debug-only screen with one field per input type, plus a TYPE_NULL terminal-style view. */
class FieldLabActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "KeyCorrect field lab"
        val dp = resources.displayMetrics.density
        val pad = (PAD_DP * dp).toInt()

        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }

        fun label(text: String) {
            column.addView(
                TextView(this).apply {
                    this.text = text
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, pad, 0, 0)
                }
            )
        }

        fun field(
            label: String,
            type: Int,
            multiLine: Boolean = false,
            imeOptions: Int = EditorInfo.IME_ACTION_UNSPECIFIED,
            initial: String = "",
        ) {
            label(label)
            column.addView(
                EditText(this).apply {
                    inputType = type
                    this.imeOptions = imeOptions
                    if (multiLine) {
                        minLines = SAMPLE_MIN_LINES
                        gravity = android.view.Gravity.TOP
                    }
                    setText(initial)
                }
            )
        }

        field("Plain text", InputType.TYPE_CLASS_TEXT)
        field(
            "Multi-line text",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            multiLine = true,
            initial = SAMPLE_PARAGRAPH,
        )
        field(
            "Password",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        field(
            "Email",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        )
        field("URL", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        field("Number", InputType.TYPE_CLASS_NUMBER)
        field("Phone", InputType.TYPE_CLASS_PHONE)
        field(
            "No personalized learning",
            InputType.TYPE_CLASS_TEXT,
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )

        label("Image field (accepts images; logs what it receives)")
        addImageField(column)

        label("TYPE_NULL terminal (tap to focus; shows key events and committed text)")
        val log =
            TextView(this).apply {
                typeface = Typeface.MONOSPACE
                textSize = LOG_TEXT_SP
                minLines = 8
                setBackgroundColor(Color.parseColor("#202020"))
                setTextColor(Color.parseColor("#B0FFB0"))
                setPadding(pad, pad, pad, pad)
            }
        val terminal = TerminalView(this, log)
        column.addView(
            terminal,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (TERMINAL_HEIGHT_DP * dp).toInt(),
            ),
        )
        column.addView(log)

        setContentView(ScrollView(this).apply { addView(column) })
    }

    /** An image-accepting field that logs what it receives, below [column]'s other fields. */
    private fun addImageField(column: LinearLayout) {
        val received = TextView(this).apply { text = "(nothing received)" }
        column.addView(
            EditText(this).apply {
                hint = "paste an image here"
                setOnReceiveContentListener(arrayOf("image/*")) { _, payload ->
                    val clip = payload.clip
                    val lines =
                        (0 until clip.itemCount).mapNotNull { i ->
                            clip.getItemAt(i).uri?.let { uri ->
                                val bytes = runCatching {
                                    contentResolver.openInputStream(uri)?.use {
                                        it.readBytes().size
                                    }
                                }
                                    .getOrNull()
                                "received ${contentResolver.getType(uri)} ($bytes bytes)"
                            }
                        }
                    received.text = lines.joinToString("\n").ifEmpty { "received no image" }
                    Log.i("FieldLab", received.text.toString())
                    null
                }
            }
        )
        column.addView(received)
        column.addView(
            Button(this).apply {
                text = "Copy test image"
                setOnClickListener { copyTestImage() }
            }
        )
    }

    /** Puts a generated 64x64 PNG on the clipboard through the debug FileProvider. */
    private fun copyTestImage() {
        val dir = File(cacheDir, "fieldlab").apply { mkdirs() }
        val file = File(dir, "test.png")
        val bitmap = Bitmap.createBitmap(TEST_IMAGE_PX, TEST_IMAGE_PX, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        val uri = FileProvider.getUriForFile(this, "$packageName.fieldlab", file)
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newUri(contentResolver, "field lab test image", uri))
        Log.i("FieldLab", "copied test image")
    }
}

/** Focusable view that asks for TYPE_NULL input and logs everything the IME sends it. */
private class TerminalView(
    context: Context,
    private val logView: TextView,
) : View(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.parseColor("#404040"))
        setOnClickListener {
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, 0)
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        return object : BaseInputConnection(this, false) {
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                record("sendKeyEvent", event)
                return true
            }

            override fun commitText(
                text: CharSequence?,
                newCursorPosition: Int,
            ): Boolean {
                append("commitText \"$text\" cursor=$newCursorPosition")
                return true
            }
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        record("onKeyDown", event)
        return true
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        record("onKeyUp", event)
        return true
    }

    private fun record(
        source: String,
        event: KeyEvent,
    ) {
        val action = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        append(
            "$source $action ${KeyEvent.keyCodeToString(event.keyCode)} meta=${metaFlags(event.metaState)}"
        )
    }

    private fun metaFlags(meta: Int): String {
        val flags =
            listOf(
                    KeyEvent.META_CTRL_ON to "CTRL",
                    KeyEvent.META_ALT_ON to "ALT",
                    KeyEvent.META_SHIFT_ON to "SHIFT",
                    KeyEvent.META_META_ON to "META",
                )
                .filter { meta and it.first != 0 }
                .map { it.second }
        return if (flags.isEmpty()) "none" else flags.joinToString("+")
    }

    private fun append(line: String) {
        Log.i(TAG, line)
        logView.append(line + "\n")
    }
}

private const val TEST_IMAGE_PX = 64
private const val PNG_QUALITY = 100

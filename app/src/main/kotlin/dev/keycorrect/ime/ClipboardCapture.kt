package dev.keycorrect.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "ClipboardCapture"

/**
 * Feeds the system clipboard into the history while the keyboard service is alive. Android delivers
 * the change only to the default keyboard (and the focused app), which is the intent. The clip is
 * read in the background; text is taken as the first item's text, an image as a stored encrypted
 * blob. Our own clipboard writes ([ClipImagePaster.CLIPBOARD_LABEL]) are ignored. Clip contents are
 * never logged.
 */
@Suppress("TooGenericExceptionCaught") // A failed read must never take the keyboard down.
internal class ClipboardCapture(
    private val context: Context,
    private val environment: AppClipboardEnvironment,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ClipboardManager.OnPrimaryClipChangedListener {
    private val manager = context.getSystemService(ClipboardManager::class.java)

    fun register() = manager?.addPrimaryClipChangedListener(this)

    fun unregister() = manager?.removePrimaryClipChangedListener(this)

    override fun onPrimaryClipChanged() {
        if (!environment.enabled) return
        scope.launch(dispatcher) {
            try {
                read()
            } catch (e: Exception) {
                // Includes SecurityException when the system refuses the read.
                Log.w(TAG, "Capture failed: ${e.javaClass.name}")
            }
        }
    }

    private fun read() {
        val clip = manager?.primaryClip
        val ours = clip?.description?.label == ClipImagePaster.CLIPBOARD_LABEL
        if (clip != null && clip.itemCount > 0 && !ours) capture(clip, clip.getItemAt(0))
    }

    private fun capture(clip: ClipData, item: ClipData.Item) {
        val imageType = imageMimeType(clip, item)
        val uri = item.uri
        if (imageType != null && uri != null) {
            environment.captureImage(imageType) { context.contentResolver.openInputStream(uri) }
        } else {
            item.coerceToText(context)?.toString()?.let(environment::captureText)
        }
    }

    private fun imageMimeType(clip: ClipData, item: ClipData.Item): String? {
        val uri = item.uri ?: return null
        val declared =
            (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) }
        val type =
            declared.firstOrNull { it.startsWith("image/", ignoreCase = true) }
                ?: context.contentResolver.getType(uri)?.takeIf {
                    it.startsWith("image/", ignoreCase = true)
                }
        return type?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    }
}

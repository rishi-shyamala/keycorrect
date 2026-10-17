package dev.keycorrect.ime

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Decodes a clip's image to a thumbnail of about [TARGET_PX] pixels (sampled, never full size). The
 * image is read in two passes over freshly opened streams (its bounds, then a sampled decode)
 * instead of being copied into one byte array first. Note that the decrypting stream of
 * [dev.keycorrect.core.data.EncryptedBlobStore] holds the whole plaintext while it is open (the GCM
 * tag must be verified before any byte is released), so callers run one decode at a time.
 */
internal object ThumbnailDecoder {
    const val TARGET_PX = 256

    /** The decoded image, or null when the stream is not an image Android can decode. */
    suspend fun decode(open: ImageSource): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().buffered().use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= TARGET_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return open()
            .buffered()
            .use { BitmapFactory.decodeStream(it, null, options) }
            ?.asImageBitmap()
    }
}

package dev.keycorrect.neural

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.UUID

/** A model the user imported. [id] names its file, `<id>.gguf`, and is what settings store. */
data class ImportedModel(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val importedAtMillis: Long,
)

/** Where an import reads from; the app builds one from a Uri chosen through the file picker. */
class ModelSource(
    /** The file name the picker shows, if known (cleaned before it is kept). */
    val displayName: String?,
    /** The size in bytes if the provider says; null or negative when unknown. */
    val sizeBytes: Long?,
    /** Opens the content; called once, the store closes it. */
    val open: () -> InputStream?,
)

/** The outcome of [ModelStore.import]. */
sealed interface ImportResult {
    /** The model is stored; [alreadyPresent] when the same file had been imported before. */
    data class Imported(val model: ImportedModel, val alreadyPresent: Boolean = false) :
        ImportResult

    /** The file doesn't start with the GGUF signature (or is too short). */
    data object NotGguf : ImportResult

    /** A GGUF of a version other than 2 or 3. */
    data class UnsupportedVersion(val version: Long) : ImportResult

    /** The file is larger than [ModelStore.MAX_MODEL_BYTES]. */
    data object TooLarge : ImportResult

    /** Not enough free storage: [neededBytes] would have to be free, [freeBytes] are. */
    data class NoSpace(val neededBytes: Long, val freeBytes: Long) : ImportResult

    /** [ModelStore.MAX_MODELS] models are stored already; delete one first. */
    data object TooManyModels : ImportResult

    /** The file couldn't be read or written; [reason] is an exception class name. */
    data class Failed(val reason: String) : ImportResult
}

/**
 * The language models the user imported (Settings → Engines → Import model), in [dir] inside the
 * app's private files. Nothing here uses the network.
 *
 * An import checks the first eight bytes (the "GGUF" signature and version 2 or 3), refuses files
 * over [MAX_MODEL_BYTES] or without room to spare, copies into a temporary file while hashing it,
 * then renames it atomically to `<first 16 hex of the SHA-256>.gguf`, so the same file is stored
 * once. A small properties file (`index.properties`) keeps the display name, size and import time;
 * a model file missing from it is still listed (named by its id). At most [MAX_MODELS] are kept.
 *
 * Not safe against two stores on the same directory, but safe for concurrent calls on one.
 */
class ModelStore
internal constructor(
    private val dir: File,
    private val clock: () -> Long,
    private val freeSpace: (File) -> Long,
) {
    constructor(
        context: Context
    ) : this(File(context.filesDir, DIR_NAME), System::currentTimeMillis, File::getUsableSpace)

    private val lock = Any()

    /** The stored models, newest import first. */
    fun list(): List<ImportedModel> =
        synchronized(lock) {
            val index = readIndex()
            val files = dir.listFiles().orEmpty().filter { it.isFile && ID_FILE.matches(it.name) }
            files
                .map { file ->
                    val id = file.name.removeSuffix(EXTENSION)
                    ImportedModel(
                        id,
                        index.getProperty("$id.name") ?: id,
                        file.length(),
                        index.getProperty("$id.time")?.toLongOrNull() ?: file.lastModified(),
                    )
                }
                .sortedWith(compareByDescending<ImportedModel> { it.importedAtMillis }.thenBy { it.id })
        }

    /** The file of model [id], or null when there is none (the id is checked, never a path). */
    fun pathOf(id: String): File? =
        file(id)?.takeIf { it.isFile }

    /** Deletes model [id] and its index entry; false when there was none. */
    fun delete(id: String): Boolean {
        val target = file(id) ?: return false
        return synchronized(lock) {
            val existed = target.delete()
            val index = readIndex()
            if (index.remove("$id.name") != null) {
                index.remove("$id.time")
                writeIndex(index)
            }
            existed
        }
    }

    /** Imports the file the user picked at [uri] through [resolver]. */
    fun import(resolver: ContentResolver, uri: Uri): ImportResult = import(sourceOf(resolver, uri))

    /** Imports [source]; see the class comment for the checks. Blocking: not for the main thread. */
    fun import(source: ModelSource): ImportResult {
        if (list().size >= MAX_MODELS) return ImportResult.TooManyModels
        val declared = source.sizeBytes?.takeIf { it >= 0 }
        if (declared != null && declared > MAX_MODEL_BYTES) return ImportResult.TooLarge
        val needed = (declared ?: 0L) + MIN_FREE_BYTES
        if (!dir.isDirectory && !dir.mkdirs()) return ImportResult.Failed("NoDirectory")
        val free = freeSpace(dir)
        if (free < needed) return ImportResult.NoSpace(needed, free)
        val temp = File(dir, "$TEMP_PREFIX${UUID.randomUUID()}$TEMP_SUFFIX")
        return try {
            val stream = source.open() ?: return ImportResult.Failed("NoStream")
            when (val copy = stream.use { copyChecked(it, temp) }) {
                is Copy.Rejected -> copy.result
                is Copy.Done -> publish(temp, copy, source.displayName)
            }
        } catch (e: IOException) {
            ImportResult.Failed(e.javaClass.simpleName)
        } catch (e: SecurityException) {
            ImportResult.Failed(e.javaClass.simpleName)
        } finally {
            temp.delete()
        }
    }

    private sealed interface Copy {
        class Done(val id: String, val size: Long) : Copy

        class Rejected(val result: ImportResult) : Copy
    }

    /** Copies [input] to [temp] if it is a GGUF within limits; otherwise says why not. */
    private fun copyChecked(input: InputStream, temp: File): Copy {
        val header = ByteArray(HEADER_BYTES)
        if (readFully(input, header) < HEADER_BYTES) return Copy.Rejected(ImportResult.NotGguf)
        if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            return Copy.Rejected(ImportResult.NotGguf)
        }
        val version = littleEndianInt(header, MAGIC.size)
        if (version != 2L && version != 3L) {
            return Copy.Rejected(ImportResult.UnsupportedVersion(version))
        }
        val digest = MessageDigest.getInstance("SHA-256").apply { update(header) }
        var total = header.size.toLong()
        temp.outputStream().use { out ->
            out.write(header)
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw IOException("Cancelled")
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_MODEL_BYTES) return Copy.Rejected(ImportResult.TooLarge)
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
            }
        }
        val id = digest.digest().take(ID_BYTES).joinToString("") { "%02x".format(Locale.ROOT, it) }
        return Copy.Done(id, total)
    }

    private fun publish(temp: File, copy: Copy.Done, displayName: String?): ImportResult {
        val name = cleanName(displayName)
        return synchronized(lock) {
            val target = File(dir, "${copy.id}$EXTENSION")
            val existing = list()
            when {
                target.isFile ->
                    ImportResult.Imported(existing.first { it.id == copy.id }, alreadyPresent = true)
                existing.size >= MAX_MODELS -> ImportResult.TooManyModels
                else -> {
                    moveInto(temp, target)
                    val now = clock()
                    val index = readIndex()
                    index.setProperty("${copy.id}.name", name)
                    index.setProperty("${copy.id}.time", now.toString())
                    writeIndex(index)
                    ImportResult.Imported(ImportedModel(copy.id, name, copy.size, now))
                }
            }
        }
    }

    private fun moveInto(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath())
        }
    }

    private fun file(id: String): File? = if (ID_PATTERN.matches(id)) File(dir, "$id$EXTENSION") else null

    private fun readIndex(): Properties {
        val props = Properties()
        val file = File(dir, INDEX_NAME)
        if (file.isFile) {
            try {
                file.inputStream().use(props::load)
            } catch (_: IOException) {
                props.clear()
            } catch (_: IllegalArgumentException) {
                props.clear()
            }
        }
        return props
    }

    private fun writeIndex(index: Properties) {
        val temp = File(dir, "$INDEX_NAME$TEMP_SUFFIX")
        try {
            temp.outputStream().use { index.store(it, null) }
            moveInto(temp, File(dir, INDEX_NAME))
        } catch (_: IOException) {
            // The models still list (by id); the names are cosmetic.
            temp.delete()
        }
    }

    companion object {
        /** The directory under the app's files. */
        const val DIR_NAME = "models"

        /** Models are limited to 2 GB. */
        const val MAX_MODEL_BYTES = 2L * 1024 * 1024 * 1024

        /** At most this many models are kept. */
        const val MAX_MODELS = 3

        /** Free space that must remain after the copy. */
        const val MIN_FREE_BYTES = 200L * 1024 * 1024

        /** The longest display name kept. */
        const val MAX_NAME_CHARS = 80

        private const val EXTENSION = ".gguf"
        private const val INDEX_NAME = "index.properties"
        private const val TEMP_PREFIX = ".import-"
        private const val TEMP_SUFFIX = ".tmp"
        private const val HEADER_BYTES = 8
        private const val ID_BYTES = 8
        private const val COPY_BUFFER = 256 * 1024
        private const val FALLBACK_NAME = "model"
        private val MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
        private val ID_PATTERN = Regex("[0-9a-f]{16}")
        private val ID_FILE = Regex("[0-9a-f]{16}\\.gguf")

        /** A picker name made safe to keep and show: no control characters or path separators. */
        internal fun cleanName(raw: String?): String {
            val cleaned =
                raw.orEmpty()
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .filter { !it.isISOControl() }
                    .trim()
                    .take(MAX_NAME_CHARS)
                    .trim()
            return cleaned.ifEmpty { FALLBACK_NAME }
        }

        private fun littleEndianInt(bytes: ByteArray, at: Int): Long {
            var value = 0L
            for (i in 3 downTo 0) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
            return value
        }

        /** Reads until [buffer] is full or the input ends; returns the bytes read. */
        private fun readFully(input: InputStream, buffer: ByteArray): Int {
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            return read
        }

        private fun sourceOf(resolver: ContentResolver, uri: Uri): ModelSource {
            var name: String? = null
            var size: Long? = null
            try {
                resolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            name = cursor.getString(0)
                            size = if (cursor.isNull(1)) null else cursor.getLong(1)
                        }
                    }
            } catch (_: SecurityException) {
                // Opening will report the problem.
            }
            return ModelSource(name, size) { resolver.openInputStream(uri) }
        }
    }
}

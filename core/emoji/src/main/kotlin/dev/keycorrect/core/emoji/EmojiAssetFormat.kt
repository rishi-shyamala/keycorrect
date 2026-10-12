package dev.keycorrect.core.emoji

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** The asset is not a valid KCE1 file. */
class EmojiFormatException(message: String) : IOException(message)

internal object EmojiAssetFormat {
    const val MAGIC = "KCE1"
    const val VERSION = 1
    const val SEP = '\t'
    const val KEYWORD_SEP = '|'
}

/** Compiles an [EmojiCatalog] to the `.kce` text format (see `core/emoji/FORMAT.md`). */
object EmojiCatalogWriter {
    fun write(catalog: EmojiCatalog, out: OutputStream) {
        val w = OutputStreamWriter(out, StandardCharsets.UTF_8)
        fun line(vararg fields: String) {
            for (f in fields) {
                require(f.none { it == '\t' || it == '\n' || it == '\r' }) {
                    "bad character in '$f'"
                }
            }
            w.write(fields.joinToString(EmojiAssetFormat.SEP.toString()))
            w.write("\n")
        }
        line(
            EmojiAssetFormat.MAGIC,
            EmojiAssetFormat.VERSION.toString(),
            catalog.unicodeVersion,
            catalog.cldrVersion,
        )
        var variants = 0
        for (g in catalog.groups) {
            line("G", g.name)
            for (e in g.emoji) {
                line(
                    "E",
                    e.value,
                    e.name,
                    e.keywords.joinToString(EmojiAssetFormat.KEYWORD_SEP.toString()),
                )
                for (v in e.variants) line("V", v.value, v.label)
                variants += e.variants.size
            }
        }
        for (g in catalog.kaomojiGroups) {
            line("C", g.name)
            for (k in g.items) line("K", k)
        }
        val bases = catalog.groups.sumOf { it.emoji.size }
        line("Z", bases.toString(), variants.toString(), catalog.kaomoji.size.toString())
        w.flush()
    }
}

internal object EmojiAssetReader {
    private const val BUFFER = 1 shl 16
    private const val HEADER_FIELDS = 4
    private const val EMOJI_FIELDS = 4
    private const val TRAILER_FIELDS = 4
    private const val UNICODE_FIELD = 2
    private const val CLDR_FIELD = 3
    private const val VARIANT_FIELDS = 3
    private const val NAME_FIELDS = 2

    /** Collects records into groups; every `flush*` closes the item being built. */
    private class Builder {
        val groups = ArrayList<EmojiGroup>()
        val kaomojiGroups = ArrayList<KaomojiGroup>()
        private val emoji = ArrayList<Emoji>()
        private val kaomoji = ArrayList<String>()
        private val variants = ArrayList<EmojiVariant>()
        private var groupName: String? = null
        private var kaomojiName: String? = null
        private var current: Emoji? = null
        var bases = 0
        var variantCount = 0

        fun group(f: List<String>): Boolean {
            if (f.size != NAME_FIELDS || f[1].isEmpty()) return false
            flushGroup()
            groupName = f[1]
            return true
        }

        fun emoji(f: List<String>): Boolean {
            val g = groupName
            if (f.size != EMOJI_FIELDS || g == null) return false
            flushEmoji()
            val keywords =
                if (f[3].isEmpty()) emptyList() else f[3].split(EmojiAssetFormat.KEYWORD_SEP)
            current = Emoji(f[1], f[2], keywords, emptyList(), g)
            bases++
            return true
        }

        fun variant(f: List<String>): Boolean {
            if (f.size != VARIANT_FIELDS || current == null) return false
            variants += EmojiVariant(f[1], f[2])
            variantCount++
            return true
        }

        fun kaomojiGroup(f: List<String>): Boolean {
            if (f.size != NAME_FIELDS || f[1].isEmpty()) return false
            flushKaomoji()
            kaomojiName = f[1]
            return true
        }

        fun kaomoji(f: List<String>): Boolean {
            if (f.size != NAME_FIELDS || kaomojiName == null) return false
            kaomoji += f[1]
            return true
        }

        fun finish() {
            flushGroup()
            flushKaomoji()
        }

        private fun flushEmoji() {
            current?.let { emoji += it.copy(variants = variants.toList()) }
            variants.clear()
            current = null
        }

        private fun flushGroup() {
            flushEmoji()
            groupName?.let { groups += EmojiGroup(it, emoji.toList()) }
            emoji.clear()
            groupName = null
        }

        private fun flushKaomoji() {
            kaomojiName?.let { kaomojiGroups += KaomojiGroup(it, kaomoji.toList()) }
            kaomoji.clear()
            kaomojiName = null
        }
    }

    fun read(input: InputStream): EmojiCatalog {
        val r = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8), BUFFER)
        val header = (r.readLine() ?: fail("empty file")).split(EmojiAssetFormat.SEP)
        if (header.size != HEADER_FIELDS || header[0] != EmojiAssetFormat.MAGIC)
            fail("not a KCE1 file")
        if (header[1] != EmojiAssetFormat.VERSION.toString())
            fail("unsupported version ${header[1]}")
        val b = Builder()
        var trailer: List<String>? = null
        var no = 1
        for (line in r.lineSequence()) {
            no++
            if (trailer != null) fail("line $no: data after trailer")
            val f = line.split(EmojiAssetFormat.SEP)
            if (f[0] == "Z") trailer = f
            if (!apply(b, f)) fail("line $no: bad record '${line.take(MAX_QUOTE)}'")
        }
        b.finish()
        val z = trailer ?: fail("truncated file (no trailer)")
        val counts = listOf(b.bases, b.variantCount, b.kaomojiGroups.sumOf { it.items.size })
        if (z.drop(1).map { it.toIntOrNull() } != counts)
            fail("trailer counts do not match the data")
        return EmojiCatalog(header[UNICODE_FIELD], header[CLDR_FIELD], b.groups, b.kaomojiGroups)
    }

    /** Applies one record; false if it is malformed. */
    private fun apply(b: Builder, f: List<String>): Boolean =
        when (f[0]) {
            "G" -> b.group(f)
            "E" -> b.emoji(f)
            "V" -> b.variant(f)
            "C" -> b.kaomojiGroup(f)
            "K" -> b.kaomoji(f)
            "Z" -> f.size == TRAILER_FIELDS
            else -> false
        }

    private const val MAX_QUOTE = 40

    private fun fail(message: String): Nothing = throw EmojiFormatException(message)
}

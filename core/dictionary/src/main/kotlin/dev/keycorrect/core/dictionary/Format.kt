package dev.keycorrect.core.dictionary

import java.nio.ByteBuffer

/** Constants and low-level codecs of the KCD1 binary format. See FORMAT.md. */
internal object Fmt {
    const val VERSION = 1
    const val HEADER_SIZE = 32
    const val SECTION_ENTRY_SIZE = 12
    const val CRC_OFFSET = 16
    const val MAX_SECTIONS = 64

    const val SEC_META = 1
    const val SEC_PARAMS = 2
    const val SEC_ALPHABET = 3
    const val SEC_TRIE_NODES = 4
    const val SEC_TRIE_SAMPLES = 5
    const val SEC_TERM_BITMAP = 6
    const val SEC_TERM_VALUES = 7
    const val SEC_MULTI_IDS = 8
    const val SEC_WORD_INFO = 9
    const val SEC_ID_TO_NODE = 10
    const val SEC_CASE_INDEX = 11
    const val SEC_CASE_POOL = 12
    const val SEC_BIGRAM_INDEX = 13
    const val SEC_BIGRAM_DATA = 14
    const val SEC_TRIGRAM_INDEX = 15
    const val SEC_TRIGRAM_DATA = 16

    const val PARAMS_SIZE = 64

    /** Sections whose bytes are covered by the header CRC (all small). */
    val CRC_SECTIONS = intArrayOf(SEC_META, SEC_PARAMS, SEC_ALPHABET)

    const val NODE_RECORD = 3
    const val SAMPLE_STRIDE = 16
    const val BITMAP_BLOCK_BYTES = 12
    const val MULTI_FLAG = 0x800000
    const val NO_NODE = 0xFFFFFF

    /** Max entries per n-gram block (skip granularity). */
    const val NGRAM_BLOCK = 32
    const val LIST_STRIDE = 32
    const val LIST_SHIFT = 5
    const val MAX_ID = 0xFFFFFF

    const val CASE_SAME = 0
    const val CASE_INITIAL = 1
    const val CASE_UPPER = 2
    const val CASE_CUSTOM = 3

    fun varintSize(v: Int): Int =
        when {
            v < 0x80 -> 1
            v < 0x4000 -> 2
            v < 0x200000 -> 3
            v < 0x10000000 -> 4
            else -> 5
        }

    /**
     * Reads an unsigned LEB128 at absolute [pos]. Bounds-checked: throws
     * [DictionaryFormatException] when [pos] is outside the buffer, the encoding is longer than 5
     * bytes, or the value does not fit a non-negative Int (corrupt data must not loop or read past
     * the buffer).
     */
    fun readVarint(buf: ByteBuffer, pos: Int): Int {
        val limit = buf.limit()
        if (pos < 0 || pos >= limit) throw DictionaryFormatException("varint outside the buffer")
        var b = buf.get(pos).toInt()
        if (b >= 0) return b
        var v = b and 0x7F
        var shift = 7
        var p = pos + 1
        while (true) {
            if (p >= limit) throw DictionaryFormatException("truncated varint")
            b = buf.get(p++).toInt()
            v = v or ((b and 0x7F) shl shift)
            if (b >= 0) {
                if (v < 0) throw DictionaryFormatException("varint overflow")
                return v
            }
            shift += 7
            if (shift > 28) throw DictionaryFormatException("varint too long")
        }
    }

    fun u8(buf: ByteBuffer, pos: Int): Int = buf.get(pos).toInt() and 0xFF

    fun u16(buf: ByteBuffer, pos: Int): Int = buf.getShort(pos).toInt() and 0xFFFF

    fun u24(buf: ByteBuffer, pos: Int): Int =
        (buf.get(pos).toInt() and 0xFF) or
            ((buf.get(pos + 1).toInt() and 0xFF) shl 8) or
            ((buf.get(pos + 2).toInt() and 0xFF) shl 16)
}

/** Case reconstruction used by the writer (to pick a pattern) and the reader. */
internal object CaseUtil {
    fun apply(key: String, pattern: Int): String =
        when (pattern) {
            Fmt.CASE_SAME -> key
            Fmt.CASE_INITIAL ->
                if (key.isEmpty()) key
                else Character.toUpperCase(key[0]).toString() + key.substring(1)
            Fmt.CASE_UPPER -> {
                val sb = StringBuilder(key.length)
                for (c in key) sb.append(Character.toUpperCase(c))
                sb.toString()
            }
            else -> throw IllegalArgumentException("pattern $pattern is not reconstructible")
        }

    fun detect(key: String, surface: String): Int =
        when {
            surface == key -> Fmt.CASE_SAME
            apply(key, Fmt.CASE_INITIAL) == surface -> Fmt.CASE_INITIAL
            apply(key, Fmt.CASE_UPPER) == surface -> Fmt.CASE_UPPER
            else -> Fmt.CASE_CUSTOM
        }
}

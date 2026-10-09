package dev.keycorrect.core.dictionary

import java.nio.ByteBuffer
import java.util.zip.CRC32

/** Shared fixtures; the small synthetic dictionary is compiled once per JVM. */
object Fixtures {
    val smallData: DictionaryData by lazy {
        SyntheticData.make(seed = 42, words = 3000, bigrams = 20000, trigrams = 8000)
    }
    val smallBytes: ByteArray by lazy { DictionaryWriter.write(smallData) }
    val small: Dictionary by lazy { Dictionary.open(ByteBuffer.wrap(smallBytes)) }
}

fun Dictionary.idOfToken(surface: String): Int =
    when (surface) {
        Dictionary.BOS_TOKEN -> Dictionary.BOS_ID
        Dictionary.EOS_TOKEN -> Dictionary.EOS_ID
        else -> wordId(surface)
    }

/** Half the quantization step: the maximum error of a dequantized value. */
fun halfStep(values: List<Float>, levels: Int): Float {
    val min = values.min()
    val max = values.max()
    return (max - min) / levels / 2f + 1e-4f
}

/** Recomputes the header CRC after a test mutated bytes covered by it. */
fun fixCrc(bytes: ByteArray) {
    val bb = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val count = bb.getInt(12)
    val tableEnd = Fmt.HEADER_SIZE + count * Fmt.SECTION_ENTRY_SIZE
    val crc = CRC32()
    val head = bytes.copyOf(tableEnd)
    for (i in 0 until 4) head[Fmt.CRC_OFFSET + i] = 0
    crc.update(head)
    for (id in Fmt.CRC_SECTIONS) {
        for (i in 0 until count) {
            val e = Fmt.HEADER_SIZE + i * Fmt.SECTION_ENTRY_SIZE
            if (bb.getInt(e) == id) crc.update(bytes, bb.getInt(e + 4), bb.getInt(e + 8))
        }
    }
    bb.putInt(Fmt.CRC_OFFSET, crc.value.toInt())
}

fun sectionEntry(bytes: ByteArray, sectionId: Int): Int {
    val bb = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until bb.getInt(12)) {
        val e = Fmt.HEADER_SIZE + i * Fmt.SECTION_ENTRY_SIZE
        if (bb.getInt(e) == sectionId) return e
    }
    error("no section $sectionId")
}

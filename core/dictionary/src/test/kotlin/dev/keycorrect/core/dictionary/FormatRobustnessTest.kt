package dev.keycorrect.core.dictionary

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.util.Random
import org.junit.Assert.assertThrows
import org.junit.Test

class FormatRobustnessTest {
    private val bytes = Fixtures.smallBytes

    private fun open(b: ByteArray) = Dictionary.open(ByteBuffer.wrap(b))

    private fun assertRejected(b: ByteArray) {
        assertThrows(DictionaryFormatException::class.java) { open(b) }
    }

    @Test
    fun deterministicBytes() {
        val again = DictionaryWriter.write(Fixtures.smallData)
        assertThat(again).isEqualTo(bytes)
        // Rebuilding the same data from scratch gives the same bytes too.
        val fresh = SyntheticData.make(seed = 42, words = 3000, bigrams = 20000, trigrams = 8000)
        assertThat(DictionaryWriter.write(fresh)).isEqualTo(bytes)
    }

    @Test
    fun inputOrderDoesNotChangeOutput() {
        val d = Fixtures.smallData
        val rnd = Random(77)
        val shuffled =
            DictionaryData(
                d.metadata,
                d.words.shuffled(rnd),
                d.bigrams.shuffled(rnd),
                d.trigrams.shuffled(rnd),
            )
        assertThat(DictionaryWriter.write(shuffled)).isEqualTo(bytes)
    }

    @Test
    fun truncatedBuffersAreRejected() {
        for (len in
            intArrayOf(0, 1, 3, 4, 8, 31, 32, 50, 100, 1000, bytes.size / 2, bytes.size - 1)) {
            assertRejected(bytes.copyOf(len))
        }
        // truncation by buffer limit rather than copy
        val bb = ByteBuffer.wrap(bytes)
        bb.limit(bytes.size - 8)
        assertThrows(DictionaryFormatException::class.java) { Dictionary.open(bb) }
    }

    @Test
    fun badMagicAndVersionAreRejected() {
        val badMagic = bytes.copyOf().also { it[0] = 'X'.code.toByte() }
        assertRejected(badMagic)
        val badVersion = bytes.copyOf().also { it[4] = 2 }
        val e = assertThrows(DictionaryFormatException::class.java) { open(badVersion) }
        assertThat(e).hasMessageThat().contains("version")
        assertRejected(ByteArray(64))
        assertRejected("not a dictionary at all, just text padding to be long enough".toByteArray())
    }

    @Test
    fun headerCorruptionIsDetectedByChecksum() {
        // section table, params and metadata are covered by the CRC
        for (section in intArrayOf(Fmt.SEC_META, Fmt.SEC_PARAMS, Fmt.SEC_ALPHABET)) {
            val e = sectionEntry(bytes, section)
            val off = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(e + 4)
            val c = bytes.copyOf()
            c[off + 1] = (c[off + 1].toInt() xor 0x55).toByte()
            val ex = assertThrows(DictionaryFormatException::class.java) { open(c) }
            assertThat(ex).hasMessageThat().contains("checksum")
        }
        val c = bytes.copyOf()
        c[Fmt.HEADER_SIZE + 5] = (c[Fmt.HEADER_SIZE + 5].toInt() xor 1).toByte()
        assertRejected(c)
    }

    @Test
    fun sectionBoundsAndLengthsAreValidated() {
        val bb = { b: ByteArray -> ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN) }
        // section extends past the end
        val e = sectionEntry(bytes, Fmt.SEC_BIGRAM_DATA)
        val c1 = bytes.copyOf()
        bb(c1).putInt(e + 8, bytes.size)
        fixCrc(c1)
        assertRejected(c1)
        // section offset beyond the buffer
        val c2 = bytes.copyOf()
        bb(c2).putInt(e + 4, bytes.size + 100)
        fixCrc(c2)
        assertRejected(c2)
        // length inconsistent with params (trie nodes)
        val e3 = sectionEntry(bytes, Fmt.SEC_TRIE_NODES)
        val c3 = bytes.copyOf()
        bb(c3).putInt(e3 + 8, bb(c3).getInt(e3 + 8) - 3)
        fixCrc(c3)
        assertRejected(c3)
        // missing section (rename id to an unknown one)
        val e4 = sectionEntry(bytes, Fmt.SEC_WORD_INFO)
        val c4 = bytes.copyOf()
        bb(c4).putInt(e4, 99)
        fixCrc(c4)
        assertRejected(c4)
        // implausible counts
        val pe = sectionEntry(bytes, Fmt.SEC_PARAMS)
        val poff = bb(bytes).getInt(pe + 4)
        val c5 = bytes.copyOf()
        bb(c5).putInt(poff, 0)
        fixCrc(c5)
        assertRejected(c5)
    }
}

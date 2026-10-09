package dev.keycorrect.core.dictionary

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.Test

class DictionaryRoundTripTest {
    private val data = Fixtures.smallData
    private val dict = Fixtures.small

    @Test
    fun metadataAndCounts() {
        assertThat(dict.metadata).isEqualTo(SyntheticData.METADATA)
        assertThat(dict.wordCount).isEqualTo(data.words.size + Dictionary.FIRST_WORD_ID)
        assertThat(dict.bigramCount).isEqualTo(data.bigrams.size)
        assertThat(dict.trigramCount).isEqualTo(data.trigrams.size)
        assertThat(dict.word(Dictionary.BOS_ID)).isEqualTo("<s>")
        assertThat(dict.word(Dictionary.EOS_ID)).isEqualTo("</s>")
        assertThat(dict.flags(Dictionary.BOS_ID) and WordFlags.SPECIAL).isNotEqualTo(0)
    }

    @Test
    fun everyWordRoundTrips() {
        val tol = halfStep(data.words.map { it.logProb }, 255)
        for (w in data.words) {
            val id = dict.wordId(w.surface)
            assertThat(id).isAtLeast(Dictionary.FIRST_WORD_ID)
            assertThat(dict.word(id)).isEqualTo(w.surface)
            assertThat(dict.logProb(id)).isWithin(tol).of(w.logProb)
            val flags = dict.flags(id)
            assertThat(flags and WordFlags.USER_MASK).isEqualTo(w.flags)
            assertThat(flags and WordFlags.HAS_CAPITAL != 0)
                .isEqualTo(w.surface != w.surface.lowercase())
            assertThat(dict.keyOf(id)).isEqualTo(w.surface.lowercase())
            assertThat(dict.idsForKey(w.surface.lowercase()).toList()).contains(id)
        }
    }

    @Test
    fun idsAreOrderedByDescendingProbability() {
        var prev = Float.POSITIVE_INFINITY
        for (id in Dictionary.FIRST_WORD_ID until dict.wordCount) {
            val lp = dict.logProb(id)
            assertThat(lp).isAtMost(prev)
            prev = lp
        }
    }

    @Test
    fun caseVariantsAccentsAndFlags() {
        // "i" -> "I"
        assertThat(dict.wordId("I")).isAtLeast(2)
        assertThat(dict.wordId("i")).isEqualTo(-1)
        assertThat(dict.idsForKey("i")).hasLength(1)
        assertThat(dict.word(dict.idsForKey("i")[0])).isEqualTo("I")
        // "iphone" -> "iPhone"
        assertThat(dict.word(dict.idsForKey("iphone")[0])).isEqualTo("iPhone")
        assertThat(dict.wordId("iphone")).isEqualTo(-1)
        assertThat(dict.wordId("IPhone")).isEqualTo(-1)
        // "us" and "US" are separate ids under one key, most probable first
        val us = dict.idsForKey("us")
        assertThat(us).hasLength(2)
        assertThat(us[0]).isLessThan(us[1])
        assertThat(dict.word(us[0])).isEqualTo("us")
        assertThat(dict.word(us[1])).isEqualTo("US")
        assertThat(dict.wordId("us")).isEqualTo(us[0])
        assertThat(dict.wordId("US")).isEqualTo(us[1])
        assertThat(dict.wordId("Us")).isEqualTo(-1)
        // custom casing and non-trivial lowercase mappings
        assertThat(dict.word(dict.wordId("McDonald"))).isEqualTo("McDonald")
        assertThat(dict.wordId("İstanbul")).isAtLeast(2)
        assertThat(dict.word(dict.wordId("İstanbul"))).isEqualTo("İstanbul")
        // accents are kept in the key
        assertThat(dict.idsForKey("café")).hasLength(2)
        assertThat(dict.idsForKey("cafe")).hasLength(0)
        assertThat(dict.wordId("naïve")).isAtLeast(2)
        // flags
        assertThat(dict.flags(dict.wordId("damn")) and WordFlags.POSSIBLY_OFFENSIVE).isNotEqualTo(0)
        assertThat(dict.flags(dict.wordId("etc")) and WordFlags.NOT_A_WORD).isNotEqualTo(0)
        assertThat(dict.flags(dict.wordId("the"))).isEqualTo(0)
        assertThat(dict.flags(dict.wordId("US")) and WordFlags.PROPER_NOUN).isNotEqualTo(0)
        assertThat(dict.wordId("The")).isEqualTo(-1)
        assertThat(dict.wordId("zzzzzzzz")).isEqualTo(-1)
        assertThat(dict.wordId("")).isEqualTo(-1)
        assertThat(data.words.count { it.flags and WordFlags.POSSIBLY_OFFENSIVE != 0 })
            .isGreaterThan(10)
    }

    @Test
    fun invalidIdsThrow() {
        org.junit.Assert.assertThrows(IndexOutOfBoundsException::class.java) { dict.word(-1) }
        org.junit.Assert.assertThrows(IndexOutOfBoundsException::class.java) {
            dict.logProb(dict.wordCount)
        }
    }

    @Test
    fun opensFromOffsetDirectAndMappedBuffers() {
        val bytes = Fixtures.smallBytes
        // Non-zero position inside a larger buffer; the caller's buffer state is untouched.
        val padded = ByteBuffer.allocate(bytes.size + 100)
        padded.position(37)
        padded.put(bytes)
        padded.position(37)
        val d1 = Dictionary.open(padded)
        assertThat(padded.position()).isEqualTo(37)
        assertThat(d1.wordId("the")).isEqualTo(dict.wordId("the"))

        // Big-endian caller buffer is fine: the dictionary sets its own view's order.
        val direct = ByteBuffer.allocateDirect(bytes.size)
        direct.put(bytes).flip()
        assertThat(Dictionary.open(direct).wordId("iPhone")).isEqualTo(dict.wordId("iPhone"))

        val file = File.createTempFile("kcd", ".bin")
        try {
            Files.write(file.toPath(), bytes)
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { ch ->
                val mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
                val d2 = Dictionary.open(mapped)
                for (w in data.words.take(500)) {
                    assertThat(d2.wordId(w.surface)).isEqualTo(dict.wordId(w.surface))
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun trailingBytesAreIgnored() {
        val d = Dictionary.open(ByteBuffer.wrap(Fixtures.smallBytes + ByteArray(50) { 7 }))
        assertThat(d.wordCount).isEqualTo(dict.wordCount)
    }

    @Test
    fun emptyDictionaryRoundTrips() {
        val bytes = DictionaryWriter.write(DictionaryData(SyntheticData.METADATA, emptyList()))
        val d = Dictionary.open(ByteBuffer.wrap(bytes))
        assertThat(d.wordCount).isEqualTo(2)
        assertThat(d.wordId("a")).isEqualTo(-1)
        assertThat(d.childCount(d.root)).isEqualTo(0)
        assertThat(d.bigramLogProb(0, 1)).isNull()
        assertThat(d.topSuccessors(0, 5)).isEmpty()
    }

    @Test
    fun writerRejectsBadInput() {
        fun write(words: List<WordEntry>, bg: List<BigramEntry> = emptyList()) =
            DictionaryWriter.write(DictionaryData(SyntheticData.METADATA, words, bg))
        val a = WordEntry("a", -1f)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { write(listOf(a, a)) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            write(listOf(WordEntry("", -1f)))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            write(listOf(WordEntry("<s>", -1f)))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            write(listOf(WordEntry("a", Float.NaN)))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            write(listOf(WordEntry("a", -1f, flags = 64)))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            write(listOf(a), listOf(BigramEntry("a", "nope", -1f)))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            write(listOf(a), listOf(BigramEntry("a", "a", -1f), BigramEntry("a", "a", -2f)))
        }
        // skipUnknownNgrams drops instead of throwing
        val ok =
            DictionaryWriter.write(
                DictionaryData(
                    SyntheticData.METADATA,
                    listOf(a),
                    listOf(BigramEntry("a", "nope", -1f)),
                ),
                skipUnknownNgrams = true,
            )
        assertThat(Dictionary.open(ByteBuffer.wrap(ok)).bigramCount).isEqualTo(0)
    }

    @Test
    fun fromCountHelpers() {
        val w = WordEntry.fromCount("x", 50, 1000)
        assertThat(w.logProb).isWithin(1e-5f).of(Math.log(0.05).toFloat())
        val b = BigramEntry.fromCount("x", "y", 5, 50)
        assertThat(b.logProb).isWithin(1e-5f).of(Math.log(0.1).toFloat())
    }

    @Test
    fun normalizeKeyKeepsAccents() {
        assertThat(Dictionary.normalizeKey("CAFÉ")).isEqualTo("café")
        assertThat(Dictionary.normalizeKey("iPhone")).isEqualTo("iphone")
    }
}

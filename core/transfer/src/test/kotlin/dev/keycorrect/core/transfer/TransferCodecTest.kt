package dev.keycorrect.core.transfer

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferCodecTest {
    private val fastIterations = TransferCodec.MIN_ITERATIONS

    private fun encrypted(pass: String = "correct horse", bundle: TransferBundle = sampleBundle()) =
        TransferCodec.write(bundle, pass.toCharArray(), fastIterations)

    @Test
    fun plainRoundTrip() {
        val bytes = TransferCodec.write(sampleBundle())
        assertThat(TransferCodec.isEncrypted(bytes)).isFalse()
        assertThat(TransferCodec.read(bytes)).isEqualTo(sampleBundle())
    }

    @Test
    fun emptyPassphraseWritesPlain() {
        val bytes = TransferCodec.write(sampleBundle(), CharArray(0))
        assertThat(TransferCodec.isEncrypted(bytes)).isFalse()
    }

    @Test
    fun encryptedRoundTripAndZeroing() {
        val bytes = encrypted()
        assertThat(TransferCodec.isEncrypted(bytes)).isTrue()
        val pass = "correct horse".toCharArray()
        assertThat(TransferCodec.read(bytes) { pass }).isEqualTo(sampleBundle())
        assertThat(pass.all { it == '\u0000' }).isTrue()
    }

    @Test
    fun writeZeroesPassphrase() {
        val pass = "secret".toCharArray()
        TransferCodec.write(sampleBundle(), pass, fastIterations)
        assertThat(pass.all { it == '\u0000' }).isTrue()
    }

    @Test
    fun encryptedFileHasNoPlaintext() {
        val text = String(encrypted(), Charsets.ISO_8859_1)
        assertThat(text).doesNotContain("Rishi")
        assertThat(text).doesNotContain("settings.json")
    }

    @Test
    fun sameInputEncryptsDifferently() {
        assertThat(encrypted()).isNotEqualTo(encrypted())
    }

    @Test
    fun wrongPassphrase() {
        assertThrows(TransferException.WrongPassphrase::class.java) {
            TransferCodec.read(encrypted()) { "nope".toCharArray() }
        }
    }

    @Test
    fun missingPassphrase() {
        assertThrows(TransferException.PassphraseRequired::class.java) {
            TransferCodec.read(encrypted())
        }
    }

    @Test
    fun tamperedCiphertextFails() {
        val bytes = encrypted()
        bytes[bytes.size - 20] = (bytes[bytes.size - 20].toInt() xor 1).toByte()
        assertThrows(TransferException.WrongPassphrase::class.java) {
            TransferCodec.read(bytes) { "correct horse".toCharArray() }
        }
    }

    @Test
    fun tamperedHeaderFails() {
        val bytes = encrypted()
        bytes[10] = (bytes[10].toInt() xor 1).toByte() // a salt byte
        assertThrows(TransferException.WrongPassphrase::class.java) {
            TransferCodec.read(bytes) { "correct horse".toCharArray() }
        }
    }

    @Test
    fun absurdIterationCountIsRefused() {
        val bytes = encrypted()
        bytes[6] = 0x7F // iterations high byte
        assertThrows(TransferException.Corrupt::class.java) {
            TransferCodec.read(bytes) { "correct horse".toCharArray() }
        }
    }

    @Test
    fun truncatedEncryptedFile() {
        assertThrows(TransferException.Corrupt::class.java) {
            TransferCodec.read(encrypted().copyOf(20)) { "x".toCharArray() }
        }
    }

    @Test
    fun newerFormatRefusedPlainAndEncrypted() {
        val newer = sampleBundle(version = FORMAT_VERSION + 1)
        val e1 =
            assertThrows(TransferException.NewerFormat::class.java) {
                TransferCodec.read(TransferCodec.write(newer))
            }
        assertThat(e1.found).isEqualTo(FORMAT_VERSION + 1)
        assertThrows(TransferException.NewerFormat::class.java) {
            TransferCodec.read(encrypted(bundle = newer)) { "correct horse".toCharArray() }
        }
    }

    @Test
    fun notABackup() {
        assertThrows(TransferException.NotABackup::class.java) {
            TransferCodec.read("hello".toByteArray())
        }
        assertThrows(TransferException.NotABackup::class.java) { TransferCodec.read(ByteArray(0)) }
    }

    @Test
    fun corruptZipAndMissingEntry() {
        assertThrows(TransferException.Corrupt::class.java) {
            TransferCodec.read(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 1, 2, 3))
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("manifest.json"))
            it.write("{}".toByteArray())
            it.closeEntry()
        }
        assertThrows(TransferException.Corrupt::class.java) {
            TransferCodec.read(out.toByteArray())
        }
    }

    @Test
    fun unknownJsonKeysAreIgnored() {
        val out = ByteArrayOutputStream()
        val plain = TransferCodec.read(TransferCodec.write(sampleBundle()))
        ZipOutputStream(out).use { z ->
            fun put(n: String, s: String) {
                z.putNextEntry(ZipEntry(n))
                z.write(s.toByteArray())
                z.closeEntry()
            }
            put(
                "manifest.json",
                """{"formatVersion":1,"appVersion":"x","createdAtMillis":1,"future":3,
                "device":{"model":"m","screenWidthPx":1,"screenHeightPx":2,"densityDpi":3,"smallestWidthDp":4,"keyHeightDp":5,"numberRow":true}}""",
            )
            put("settings.json", """{"values":{}}""")
            put("dictionary.json", "{}")
            put("learning.json", "{}")
            put("touch.json", "{}")
            put("extra.bin", "ignored")
        }
        val b = TransferCodec.read(out.toByteArray())
        assertThat(b.manifest.device.model).isEqualTo("m")
        assertThat(b.dictionary.words).isEmpty()
        assertThat(plain.manifest.formatVersion).isEqualTo(1)
    }

    @Test
    fun zipBombByInflatedSizeIsRefused() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            val zeros = ByteArray(64 * 1024)
            repeat(64) { z.write(zeros) } // 4 MB of zeros, a few KB compressed
            z.closeEntry()
        }
        val bytes = out.toByteArray()
        assertThat(bytes.size).isLessThan(100_000)
        assertThrows(TransferException.TooLarge::class.java) {
            TransferCodec.read(bytes, TransferLimits(maxUncompressedBytes = 1024 * 1024))
        }
    }

    @Test
    fun tooManyEntriesIsRefused() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            repeat(10) {
                z.putNextEntry(ZipEntry("f$it"))
                z.closeEntry()
            }
        }
        assertThrows(TransferException.TooLarge::class.java) {
            TransferCodec.read(out.toByteArray(), TransferLimits(maxEntries = 5))
        }
    }

    @Test
    fun duplicateEntriesAreRefused() {
        val out = ByteArrayOutputStream()
        // ZipOutputStream refuses duplicate names, so build the duplicate through raw bytes.
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("aaaa.json"))
            z.closeEntry()
            z.putNextEntry(ZipEntry("bbbb.json"))
            z.closeEntry()
        }
        val bytes = out.toByteArray()
        val s = String(bytes, Charsets.ISO_8859_1).replace("bbbb.json", "aaaa.json")
        assertThrows(TransferException.Corrupt::class.java) {
            TransferCodec.read(s.toByteArray(Charsets.ISO_8859_1))
        }
    }

    // ------------------------------------------------------------- passphrase handling

    @Test
    fun theSameTypedPassphraseOpensTheFileWhateverItsUnicodeForm() {
        val composed = "caf\u00e9"
        val decomposed = "cafe\u0301"
        assertThat(composed).isNotEqualTo(decomposed)
        val bytes = TransferCodec.write(sampleBundle(), composed.toCharArray(), fastIterations)
        assertThat(TransferCodec.read(bytes) { decomposed.toCharArray() }).isEqualTo(sampleBundle())
        val other = TransferCodec.write(sampleBundle(), decomposed.toCharArray(), fastIterations)
        assertThat(TransferCodec.read(other) { composed.toCharArray() }).isEqualTo(sampleBundle())
    }

    @Test
    fun theDecodePassphraseIsZeroedEvenWhenItIsWrong() {
        val pass = "nope".toCharArray()
        assertThrows(TransferException.WrongPassphrase::class.java) {
            TransferCodec.read(encrypted()) { pass }
        }
        assertThat(pass.all { it == '\u0000' }).isTrue()
    }

    @Test
    fun theNormalisedCopyDoesNotChangeTheCallersArray() {
        val pass = "cafe\u0301".toCharArray()
        TransferCodec.write(sampleBundle(), pass.copyOf(), fastIterations)
        // write() zeroes only the array it was given; ours is a separate copy here.
        assertThat(String(pass)).isEqualTo("cafe\u0301")
    }

    // ------------------------------------------------------------- reading a file safely

    private val small = TransferLimits(maxFileBytes = 1000)

    @Test
    fun readFileReadsExactlyTheReportedSize() {
        val data = ByteArray(500) { it.toByte() }
        assertThat(TransferCodec.readFile(data.inputStream(), 500, small)).isEqualTo(data)
        assertThat(TransferCodec.readFile(data.inputStream(), null, small)).isEqualTo(data)
    }

    @Test
    fun readFileRefusesAReportedSizeOverTheLimitWithoutReading() {
        val input =
            object : java.io.InputStream() {
                override fun read(): Int = error("must not read")
            }
        assertThrows(TransferException.TooLarge::class.java) {
            TransferCodec.readFile(input, 1001, small)
        }
    }

    @Test
    fun readFileRefusesAStreamLongerThanReported() {
        assertThrows(TransferException.TooLarge::class.java) {
            TransferCodec.readFile(ByteArray(600).inputStream(), 500, small)
        }
        assertThrows(TransferException.TooLarge::class.java) {
            TransferCodec.readFile(ByteArray(1001).inputStream(), null, small)
        }
    }

    @Test
    fun readFileToleratesAStreamShorterThanReported() {
        val data = ByteArray(300) { 7 }
        assertThat(TransferCodec.readFile(data.inputStream(), 500, small)).isEqualTo(data)
        assertThat(TransferCodec.readFile(ByteArray(1000).inputStream(), null, small))
            .hasLength(1000)
    }

    @Test
    fun defaultLimitsAreSizedForAPhone() {
        val l = TransferLimits()
        assertThat(l.maxFileBytes).isEqualTo(48L * 1024 * 1024)
        assertThat(l.maxUncompressedBytes).isEqualTo(64L * 1024 * 1024)
    }

    // ------------------------------------------------------------- backup id

    @Test
    fun backupIdIsStableAndTellsBackupsApart() {
        val m = sampleBundle().manifest
        assertThat(m.backupId()).isEqualTo(m.copy().backupId())
        assertThat(m.backupId()).hasLength(24)
        assertThat(m.backupId())
            .isNotEqualTo(m.copy(createdAtMillis = m.createdAtMillis + 1).backupId())
        assertThat(m.backupId())
            .isNotEqualTo(m.copy(device = m.device.copy(model = "x")).backupId())
        assertThat(m.backupId()).isNotEqualTo(m.copy(appVersion = "0.6.0").backupId())
    }
}

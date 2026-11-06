package dev.keycorrect.neural

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Before
import org.junit.Test

class ModelStoreTest {
    private lateinit var dir: File
    private var now = 1_000L
    private var free = Long.MAX_VALUE / 2
    private lateinit var store: ModelStore

    @Before
    fun setUp() {
        dir = File(Files.createTempDirectory("models").toFile(), "models")
        store = ModelStore(dir, { now }, { free })
    }

    @After
    fun tearDown() {
        dir.parentFile.deleteRecursively()
    }

    private fun gguf(version: Int = 3, body: String = "weights"): ByteArray =
        byteArrayOf(0x47, 0x47, 0x55, 0x46, version.toByte(), 0, 0, 0) + body.toByteArray()

    private fun source(
        bytes: ByteArray,
        name: String? = "tiny.gguf",
        size: Long? = bytes.size.toLong(),
    ) = ModelSource(name, size) { ByteArrayInputStream(bytes) }

    private fun idOf(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") {
            "%02x".format(it)
        }

    @Test
    fun importsAValidGgufNamedByItsHash() {
        val bytes = gguf()
        val result = store.import(source(bytes)) as ImportResult.Imported

        assertThat(result.alreadyPresent).isFalse()
        assertThat(result.model.id).isEqualTo(idOf(bytes))
        assertThat(result.model.displayName).isEqualTo("tiny.gguf")
        assertThat(result.model.sizeBytes).isEqualTo(bytes.size.toLong())
        assertThat(result.model.importedAtMillis).isEqualTo(1_000L)
        assertThat(store.pathOf(result.model.id)!!.readBytes()).isEqualTo(bytes)
        assertThat(store.pathOf(result.model.id)!!.name).isEqualTo("${idOf(bytes)}.gguf")
        assertThat(dir.listFiles()!!.map { it.name }).doesNotContain(".tmp")
        assertThat(dir.listFiles()!!.filter { it.name.endsWith(".tmp") }).isEmpty()
    }

    @Test
    fun acceptsVersionsTwoAndThreeOnly() {
        assertThat(store.import(source(gguf(2)))).isInstanceOf(ImportResult.Imported::class.java)
        assertThat(store.import(source(gguf(1)))).isEqualTo(ImportResult.UnsupportedVersion(1))
        assertThat(store.import(source(gguf(4)))).isEqualTo(ImportResult.UnsupportedVersion(4))
    }

    @Test
    fun refusesWhatIsNotGguf() {
        assertThat(store.import(source("hello world, not a model".toByteArray())))
            .isEqualTo(ImportResult.NotGguf)
        assertThat(store.import(source(byteArrayOf(0x47, 0x47)))).isEqualTo(ImportResult.NotGguf)
        assertThat(store.import(source(ByteArray(0)))).isEqualTo(ImportResult.NotGguf)
        assertThat(store.list()).isEmpty()
        assertThat(dir.listFiles()!!.filter { it.name.endsWith(".tmp") }).isEmpty()
    }

    @Test
    fun refusesWhatIsOverTwoGigabytes() {
        val declared = source(gguf(), size = ModelStore.MAX_MODEL_BYTES + 1)

        assertThat(store.import(declared)).isEqualTo(ImportResult.TooLarge)
    }

    @Test
    fun refusesWhenAStreamOfUnknownSizeGrowsPastTheLimit() {
        val endless =
            object : InputStream() {
                var sent = 0L

                override fun read(): Int = 0

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (sent < 8) {
                        val head = gguf(3, "")
                        System.arraycopy(head, sent.toInt(), b, off, 1)
                        sent++
                        return 1
                    }
                    sent += len
                    return len
                }
            }

        val result = store.import(ModelSource("big.gguf", null) { endless })

        assertThat(result).isEqualTo(ImportResult.TooLarge)
        assertThat(dir.listFiles()!!.filter { it.name.endsWith(".tmp") }).isEmpty()
    }

    @Test
    fun needsFreeSpaceForTheFileAndTwoHundredMegabytes() {
        val bytes = gguf()
        free = bytes.size + ModelStore.MIN_FREE_BYTES - 1

        val result = store.import(source(bytes))
        assertThat(result)
            .isEqualTo(ImportResult.NoSpace(bytes.size + ModelStore.MIN_FREE_BYTES, free))
        free = bytes.size + ModelStore.MIN_FREE_BYTES
        assertThat(store.import(source(bytes))).isInstanceOf(ImportResult.Imported::class.java)
    }

    @Test
    fun keepsAtMostThreeModels() {
        repeat(3) {
            assertThat(store.import(source(gguf(3, "m$it"))))
                .isInstanceOf(ImportResult.Imported::class.java)
        }

        assertThat(store.import(source(gguf(3, "m4")))).isEqualTo(ImportResult.TooManyModels)
        assertThat(store.list()).hasSize(3)
        store.delete(store.list().first().id)
        assertThat(store.import(source(gguf(3, "m4"))))
            .isInstanceOf(ImportResult.Imported::class.java)
    }

    @Test
    fun theSameFileIsStoredOnce() {
        val first = store.import(source(gguf())) as ImportResult.Imported
        now = 2_000L
        val second = store.import(source(gguf(), name = "other.gguf")) as ImportResult.Imported

        assertThat(second.alreadyPresent).isTrue()
        assertThat(second.model).isEqualTo(first.model)
        assertThat(store.list()).hasSize(1)
    }

    @Test
    fun listsNewestFirstAndSurvivesANewStoreObject() {
        val a = store.import(source(gguf(3, "a"), name = "a.gguf")) as ImportResult.Imported
        now = 5_000L
        val b = store.import(source(gguf(3, "b"), name = "b.gguf")) as ImportResult.Imported

        val reopened = ModelStore(dir, { now }, { free })
        assertThat(reopened.list().map { it.id }).containsExactly(b.model.id, a.model.id).inOrder()
        assertThat(reopened.list().map { it.displayName })
            .containsExactly("b.gguf", "a.gguf")
            .inOrder()
        assertThat(reopened.list().last().importedAtMillis).isEqualTo(1_000L)
    }

    @Test
    fun deleteRemovesTheFileAndTheIndexEntry() {
        val m = (store.import(source(gguf())) as ImportResult.Imported).model

        assertThat(store.delete(m.id)).isTrue()
        assertThat(store.pathOf(m.id)).isNull()
        assertThat(store.list()).isEmpty()
        assertThat(store.delete(m.id)).isFalse()
        assertThat(File(dir, "index.properties").readText()).doesNotContain(m.id)
    }

    @Test
    fun idsThatAreNotIdsNeverReachTheFileSystem() {
        val outside = File(dir.parentFile, "secret.gguf").apply { writeText("x") }

        for (id in listOf("../secret", "", "0123456789ABCDEF", "0123456789abcde", "a/../../b")) {
            assertThat(store.pathOf(id)).isNull()
            assertThat(store.delete(id)).isFalse()
        }
        assertThat(outside.exists()).isTrue()
    }

    @Test
    fun aFileWithoutAnIndexEntryIsStillListed() {
        File(dir, "0123456789abcdef.gguf").apply {
            parentFile.mkdirs()
            writeText("xyz")
        }

        val listed = store.list().single()
        assertThat(listed.id).isEqualTo("0123456789abcdef")
        assertThat(listed.displayName).isEqualTo("0123456789abcdef")
        assertThat(listed.sizeBytes).isEqualTo(3L)
    }

    @Test
    fun displayNamesAreCleaned() {
        assertThat(ModelStore.cleanName("../../etc/passwd")).isEqualTo("passwd")
        assertThat(ModelStore.cleanName("a\nb\u0000c.gguf")).isEqualTo("abc.gguf")
        assertThat(ModelStore.cleanName(null)).isEqualTo("model")
        assertThat(ModelStore.cleanName("   ")).isEqualTo("model")
        assertThat(ModelStore.cleanName("x".repeat(500))).hasLength(ModelStore.MAX_NAME_CHARS)
        assertThat(ModelStore.cleanName("C:\\models\\small=name:1.gguf"))
            .isEqualTo("small=name:1.gguf")
    }

    @Test
    fun anUnusualNameSurvivesTheIndex() {
        val name = "mödel #1 = ünï:\\x.gguf"
        val m = (store.import(source(gguf(), name = name)) as ImportResult.Imported).model

        assertThat(ModelStore(dir, { now }, { free }).list().single().displayName)
            .isEqualTo(ModelStore.cleanName(name))
        assertThat(m.displayName).isEqualTo(ModelStore.cleanName(name))
    }

    @Test
    fun aReadFailureIsReportedAndLeavesNothing() {
        val broken =
            ModelSource("a.gguf", null) {
                object : InputStream() {
                    var n = 0

                    override fun read(): Int = throw IOException("disk")

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val head = gguf(3, "")
                        if (n < 8) {
                            b[off] = head[n++]
                            return 1
                        }
                        throw IOException("disk")
                    }
                }
            }

        assertThat(store.import(broken)).isEqualTo(ImportResult.Failed("IOException"))
        assertThat(store.list()).isEmpty()
        assertThat(dir.listFiles()!!.filter { it.name.endsWith(".tmp") }).isEmpty()
    }
}

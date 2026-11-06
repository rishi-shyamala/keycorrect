package dev.keycorrect.neural

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Before
import org.junit.Test

class NeuralEngineCoreTest {
    private class FakeModel(val label: String) : LoadedModel {
        var closed = false

        override fun score(context: String, candidates: Array<String>, timeoutMs: Long) =
            if (closed) null else FloatArray(candidates.size) { -1f - it }

        override fun generate(prompt: String, maxTokens: Int, timeoutMs: Long) =
            if (closed) null else "x"

        override fun describe() = label

        override fun close() {
            closed = true
        }
    }

    private class FakeRuntime : ModelRuntime {
        val opened = mutableListOf<FakeModel>()
        var fail = false
        var lastThreads = 0
        var lastOffset = -1L
        var lastPath: String? = null
        var problem: String? = null

        /** Runs while a model is being opened (what a crash would interrupt). */
        var duringOpen: () -> Unit = {}

        override fun problem() = problem

        override fun openFile(path: String, threads: Int): LoadedModel? {
            lastPath = path
            lastThreads = threads
            duringOpen()
            return open("file")
        }

        override fun openFd(fd: Int, offset: Long, threads: Int): LoadedModel? {
            lastOffset = offset
            lastThreads = threads
            duringOpen()
            return open("pack")
        }

        private fun open(label: String) = if (fail) null else FakeModel(label).also(opened::add)
    }

    /** Runs tasks when the test says, to see the "loading" state. */
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private lateinit var root: File
    private lateinit var model: File
    private val runtime = FakeRuntime()
    private val queue = Queue()
    private var pack: DefaultModel = DefaultModel.Missing("NotInstalled")
    private var packClosed = false
    private var restarts = 0

    private fun core() = NeuralEngineCore(root, { runtime }, { pack }, queue, { restarts++ })

    @Before
    fun setUp() {
        root = Files.createTempDirectory("engine").toFile()
        model =
            File(root, "models/a.gguf").apply {
                parentFile.mkdirs()
                writeText("x")
            }
        pack = DefaultModel.Found(7, 4096L, AutoCloseable { packClosed = true })
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun startsWithoutAModel() {
        val c = core()

        assertThat(c.status).isEqualTo("no_model")
        assertThat(c.score("a", arrayOf("b"), 100)).isNull()
        assertThat(c.generate("a", 3, 100)).isNull()
    }

    @Test
    fun loadsAnImportedModelInTheBackground() {
        val c = core()
        c.configure(model.absolutePath, false, 2)

        assertThat(c.status).isEqualTo("loading")
        assertThat(c.score("a", arrayOf("b"), 100)).isNull()
        queue.runAll()
        assertThat(c.status).isEqualTo("ready")
        assertThat(c.modelDescription).isEqualTo("file")
        assertThat(runtime.lastThreads).isEqualTo(2)
        assertThat(c.score("a", arrayOf("b", "c"), 100))
            .usingExactEquality()
            .containsExactly(-1f, -2f)
            .inOrder()
        assertThat(c.generate("a", 3, 100)).isEqualTo("x")
    }

    @Test
    fun loadsThePackModelFromItsFd() {
        val c = core()
        c.configure(null, true, 0)
        queue.runAll()

        assertThat(c.status).isEqualTo("ready")
        assertThat(runtime.lastOffset).isEqualTo(4096L)
        assertThat(packClosed).isTrue()
        assertThat(c.modelDescription).isEqualTo("pack")
    }

    @Test
    fun aPackProblemIsTheStatus() {
        pack = DefaultModel.Missing("SignatureMismatch")
        val c = core()
        c.configure(null, true, 0)
        queue.runAll()

        assertThat(c.status).isEqualTo("error:SignatureMismatch")
        assertThat(restarts).isEqualTo(0)
        assertThat(c.score("a", arrayOf("b"), 100)).isNull()
    }

    @Test
    fun aPackThatNeedsARestartRestartsTheProcess() {
        pack = DefaultModel.Missing("NeedsRestart", needsRestart = true)
        val c = core()
        c.configure(null, true, 0)
        queue.runAll()

        assertThat(c.status).isEqualTo("error:NeedsRestart")
        assertThat(restarts).isEqualTo(1)
    }

    @Test
    fun pathsOutsideTheFilesDirAreRejected() {
        val outside = Files.createTempFile("evil", ".gguf").toFile()
        val c = core()
        try {
            for (path in
                listOf(
                    outside.absolutePath,
                    File(root, "models/../../" + outside.name).path,
                    "relative/a.gguf",
                    root.absolutePath,
                )) {
                c.configure(path, false, 0)
                assertThat(c.status).isEqualTo("error:PathRejected")
            }
            assertThat(queue.tasks).isEmpty()
            assertThat(runtime.opened).isEmpty()
        } finally {
            outside.delete()
        }
    }

    @Test
    fun aFileInsideTheFilesDirButOutsideTheModelsDirIsRejected() {
        val stray = File(root, "stray.gguf").apply { writeText("x") }
        val c = core()
        c.configure(stray.absolutePath, false, 0)

        assertThat(c.status).isEqualTo("error:PathRejected")
        assertThat(queue.tasks).isEmpty()
    }

    @Test
    fun aRuntimeThatCannotRunOnThisCpuNeverOpensAnything() {
        runtime.problem = "UnsupportedCpu"
        val c = core()
        c.configure(model.absolutePath, false, 0)
        queue.runAll()
        assertThat(c.status).isEqualTo("error:UnsupportedCpu")
        c.configure(null, true, 0)
        queue.runAll()
        assertThat(c.status).isEqualTo("error:UnsupportedCpu")

        assertThat(runtime.opened).isEmpty()
        assertThat(runtime.lastPath).isNull()
        assertThat(runtime.lastOffset).isEqualTo(-1L)
    }

    private fun markers() = File(root, "load-markers").listFiles().orEmpty().toList()

    @Test
    fun aMarkerExistsWhileAModelLoadsAndIsGoneAfterwards() {
        var during = emptyList<File>()
        runtime.duringOpen = { during = markers() }
        val c = core()
        c.configure(model.absolutePath, false, 0)
        queue.runAll()

        assertThat(during).hasSize(1)
        assertThat(markers()).isEmpty()
        assertThat(c.status).isEqualTo("ready")
    }

    @Test
    fun aMarkerIsRemovedWhenTheLoadFailsToo() {
        runtime.fail = true
        val c = core()
        c.configure(model.absolutePath, false, 0)
        queue.runAll()

        assertThat(c.status).isEqualTo("error:LoadFailed")
        assertThat(markers()).isEmpty()
    }

    /** Runs a load and keeps the marker it left in the middle, as a dying process would. */
    private fun leaveMarkerOf(configure: (NeuralEngineCore) -> Unit) {
        var saved = emptyMap<String, String>()
        runtime.duringOpen = { saved = markers().associate { it.name to it.readText() } }
        configure(core())
        queue.runAll()
        runtime.duringOpen = {}
        runtime.opened.clear()
        File(root, "load-markers").mkdirs()
        for ((name, text) in saved) File(root, "load-markers/$name").writeText(text)
    }

    @Test
    fun aModelThatCrashedTheProcessIsNotTriedAgain() {
        leaveMarkerOf { it.configure(model.absolutePath, false, 0) }
        val c = core()

        c.configure(model.absolutePath, false, 0)
        queue.runAll()
        assertThat(c.status).isEqualTo("error:CrashedOnLoad")
        c.configure(null, false, 0)
        c.configure(model.absolutePath, false, 4)
        queue.runAll()
        assertThat(c.status).isEqualTo("error:CrashedOnLoad")
        assertThat(runtime.opened).isEmpty()
        // The marker stays: the refusal is lasting.
        assertThat(markers()).hasSize(1)
    }

    @Test
    fun aChangedFileOrAnotherModelIsNotRefused() {
        leaveMarkerOf { it.configure(model.absolutePath, false, 0) }
        model.writeText("a longer file now")
        val c = core()
        c.configure(model.absolutePath, false, 0)
        queue.runAll()
        assertThat(c.status).isEqualTo("ready")

        val other = File(root, "models/b.gguf").apply { writeText("y") }
        c.configure(other.absolutePath, false, 0)
        queue.runAll()
        assertThat(c.status).isEqualTo("ready")
    }

    @Test
    fun aPackModelThatCrashedIsRefusedUntilThePackChanges() {
        pack = DefaultModel.Found(7, 4096L, AutoCloseable { packClosed = true }, "pack:1:2:4096")
        leaveMarkerOf { it.configure(null, true, 0) }

        val c = core()
        c.configure(null, true, 0)
        queue.runAll()
        assertThat(c.status).isEqualTo("error:CrashedOnLoad")
        assertThat(packClosed).isTrue() // the descriptor is still released
        assertThat(runtime.opened).isEmpty()

        // Updated pack: another lastUpdateTime/version, so another identity.
        pack = DefaultModel.Found(7, 4096L, AutoCloseable { packClosed = true }, "pack:5:3:4096")
        c.release()
        c.configure(null, true, 0)
        queue.runAll()
        assertThat(c.status).isEqualTo("ready")
    }

    @Test
    fun aSymlinkOutOfTheFilesDirIsRejected() {
        val outside = Files.createTempFile("evil", ".gguf").toFile()
        val link = File(root, "models/link.gguf")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val c = core()
        try {
            c.configure(link.absolutePath, false, 0)
            assertThat(c.status).isEqualTo("error:PathRejected")
        } finally {
            outside.delete()
        }
    }

    @Test
    fun aMissingFileIsAnError() {
        val c = core()
        c.configure(File(root, "models/none.gguf").absolutePath, false, 0)

        assertThat(c.status).isEqualTo("error:FileMissing")
    }

    @Test
    fun aRuntimeThatCannotOpenIsAnError() {
        runtime.fail = true
        val c = core()
        c.configure(model.absolutePath, false, 0)
        queue.runAll()

        assertThat(c.status).isEqualTo("error:LoadFailed")
    }

    @Test
    fun theSameConfigureAgainDoesNothingButAnErrorIsRetried() {
        val c = core()
        c.configure(model.absolutePath, false, 2)
        c.configure(model.absolutePath, false, 2)
        queue.runAll()
        c.configure(model.absolutePath, false, 2)
        queue.runAll()

        assertThat(runtime.opened).hasSize(1)

        runtime.fail = true
        c.configure(model.absolutePath, false, 3)
        queue.runAll()
        assertThat(c.status).isEqualTo("error:LoadFailed")
        runtime.fail = false
        c.configure(model.absolutePath, false, 3)
        queue.runAll()
        assertThat(c.status).isEqualTo("ready")
    }

    @Test
    fun aNewConfigureReplacesTheModelAndClosesTheOld() {
        val c = core()
        c.configure(model.absolutePath, false, 0)
        queue.runAll()
        val first = runtime.opened.single()
        c.configure(null, true, 0)
        queue.runAll()

        assertThat(first.closed).isTrue()
        assertThat(c.modelDescription).isEqualTo("pack")
    }

    @Test
    fun aConfigureThatSupersedesALoadClosesTheStaleModel() {
        val c = core()
        c.configure(model.absolutePath, false, 0)
        c.configure(null, true, 0)
        queue.runAll()

        assertThat(runtime.opened).hasSize(2)
        assertThat(runtime.opened[0].closed).isTrue()
        assertThat(c.modelDescription).isEqualTo("pack")
    }

    @Test
    fun neitherOptionReleasesTheModel() {
        val c = core()
        c.configure(model.absolutePath, false, 0)
        queue.runAll()
        c.configure(null, false, 0)
        queue.runAll()

        assertThat(c.status).isEqualTo("no_model")
        assertThat(runtime.opened.single().closed).isTrue()
    }

    @Test
    fun releaseDropsTheModelUntilTheNextConfigure() {
        val c = core()
        c.configure(model.absolutePath, false, 0)
        queue.runAll()
        c.release()
        queue.runAll()

        assertThat(c.status).isEqualTo("no_model")
        assertThat(runtime.opened.single().closed).isTrue()
        assertThat(c.score("a", arrayOf("b"), 100)).isNull()
        c.configure(model.absolutePath, false, 0)
        queue.runAll()
        assertThat(c.status).isEqualTo("ready")
        assertThat(runtime.opened).hasSize(2)
    }

    @Test
    fun releaseDuringALoadDiscardsTheResult() {
        val c = core()
        c.configure(model.absolutePath, false, 0)
        c.release()
        queue.runAll()

        assertThat(c.status).isEqualTo("no_model")
        assertThat(runtime.opened.single().closed).isTrue()
    }
}

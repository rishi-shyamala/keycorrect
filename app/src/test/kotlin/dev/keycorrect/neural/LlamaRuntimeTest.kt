package dev.keycorrect.neural

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LlamaRuntimeTest {
    private fun cpuInfo(vararg featureLines: String) =
        featureLines.withIndex().joinToString("\n") { (i, f) ->
            "processor\t: $i\nBogoMIPS\t: 38.40\nFeatures\t: $f\nCPU implementer\t: 0x41\n"
        }

    private val modern = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp asimddp"

    @Test
    fun arm64NeedsDotProductAndHalfPrecisionOnEveryCore() {
        assertThat(cpuSupports("arm64-v8a", cpuInfo(modern, modern))).isTrue()
        assertThat(cpuSupports("arm64-v8a", cpuInfo("fp asimd fphp asimdhp asimddp"))).isTrue()
        assertThat(cpuSupports("arm64-v8a", cpuInfo("fp asimd asimdhp asimddp"))).isTrue()
        assertThat(cpuSupports("arm64-v8a", cpuInfo("fp asimd fphp asimddp"))).isTrue()
        // One core without dotprod, or without half precision, is enough to crash.
        assertThat(cpuSupports("arm64-v8a", cpuInfo(modern, "fp asimd fphp asimdhp"))).isFalse()
        assertThat(cpuSupports("arm64-v8a", cpuInfo("fp asimd asimddp"))).isFalse()
        assertThat(cpuSupports("arm64-v8a", cpuInfo("fp asimd evtstrm"))).isFalse()
    }

    @Test
    fun anUnreadableOrEmptyCpuListOnArmCountsAsUnsupported() {
        assertThat(cpuSupports("arm64-v8a", null)).isFalse()
        assertThat(cpuSupports("arm64-v8a", "")).isFalse()
        assertThat(cpuSupports("arm64-v8a", "processor\t: 0\n")).isFalse()
    }

    @Test
    fun x86_64IsAcceptedAndOtherAbisAreNot() {
        assertThat(cpuSupports("x86_64", null)).isTrue()
        assertThat(cpuSupports("armeabi-v7a", cpuInfo(modern))).isFalse()
        assertThat(cpuSupports(null, cpuInfo(modern))).isFalse()
    }

    @Test
    fun anUnsupportedCpuNeverLocatesOrLoadsTheLibrary() {
        var located = 0
        val runtime = LlamaRuntime({ located++ >= 0 }, { false })

        assertThat(runtime.problem()).isEqualTo("UnsupportedCpu")
        assertThat(runtime.openFile("/m.gguf", 2)).isNull()
        assertThat(runtime.openFd(5, 0L, 2)).isNull()
        assertThat(located).isEqualTo(0)
    }

    @Test
    fun aSupportedCpuReportsNoProblem() {
        assertThat(LlamaRuntime({ false }, { true }).problem()).isNull()
    }

    @Test
    fun permanentFailuresAreTheTwoThatRetryingCannotFix() {
        assertThat(EngineFailure.isPermanent("error:UnsupportedCpu")).isTrue()
        assertThat(EngineFailure.isPermanent("error:CrashedOnLoad")).isTrue()
        assertThat(EngineFailure.isPermanent("error:LoadFailed")).isFalse()
        assertThat(EngineFailure.isPermanent("no_model")).isFalse()
        assertThat(EngineFailure.isPermanent(null)).isFalse()
    }
}

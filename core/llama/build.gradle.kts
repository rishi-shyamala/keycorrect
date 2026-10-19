plugins { id("keycorrect.jvm.library") }

// The integration tests need the host build of the native library (tools/llama/build-host.sh);
// KC_LLAMA_LIB points at it. Without it they are skipped.
tasks.withType<Test>().configureEach {
    val lib = providers.environmentVariable("KC_LLAMA_LIB").orElse("")
    systemProperty("kc.llama.lib", lib.get())
    inputs.property("kcLlamaLib", lib)
    testLogging { showStandardStreams = true }
}

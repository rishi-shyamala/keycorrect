plugins { id("keycorrect.jvm.library") }

// FUTO Swipe's encoder on ExecuTorch (docs/PHASE10.md, 10a-2): the Kotlin API, the JNI binding and
// the C++ glue (src/main/cpp, built by tools/swipenet/build.sh, packaged by :swipepack).
dependencies { implementation(project(":core:layout")) }

// The golden and latency tests need the host build of the native library
// (tools/swipenet/build.sh host, KC_SWIPENET_LIB) and the model from
// tools/data/fetch-swipe-model.sh (KC_SWIPENET_MODEL, else data/raw/swipenet/ here). Without them
// they are skipped; the resampler and layout tests always run.
tasks.withType<Test>().configureEach {
    val lib = providers.environmentVariable("KC_SWIPENET_LIB").orElse("")
    val model =
        providers
            .environmentVariable("KC_SWIPENET_MODEL")
            .orElse(
                rootProject.layout.projectDirectory
                    .file("data/raw/swipenet/honorable_sturgeon/model_int8.pte")
                    .asFile
                    .path
            )
    systemProperty("kc.swipenet.lib", lib.get())
    systemProperty("kc.swipenet.model", model.get())
    inputs.property("kcSwipenetLib", lib)
    inputs.property("kcSwipenetModel", model)
    testLogging { showStandardStreams = true }
}

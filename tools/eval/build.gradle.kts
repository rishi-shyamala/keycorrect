plugins {
    id("keycorrect.jvm.library")
    application
}

application {
    mainClass.set("dev.keycorrect.tools.eval.MainKt")
    // One ClassicEngine per worker thread plus per-sentence bookkeeping; the dictionary is mapped.
    applicationDefaultJvmArgs = listOf("-Xmx2g", "-XX:+UseParallelGC")
}

// `./gradlew :tools:eval:run --args="..."` resolves relative paths (data/processed, app/src/...)
// against the repository root, not this module's directory.
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }

dependencies {
    implementation(project(":core:decoder"))
    implementation(project(":core:dictionary"))
    implementation(project(":core:layout"))
    implementation(project(":core:learning"))
    implementation(project(":core:llama"))
}

plugins {
    id("keycorrect.jvm.library")
    application
}

application {
    mainClass.set("dev.keycorrect.tools.dictbuild.MainKt")
    // The `count` pipeline streams its sources and keeps n-gram tables in primitive hash maps;
    // on the full Simple English Wikipedia + Tatoeba corpus peak heap is ~1.5 GB, so 3 GB is
    // comfortable inside the 4 GB Gradle default.
    applicationDefaultJvmArgs = listOf("-Xmx3g")
}

// `./gradlew :tools:dictbuild:run --args="count ..."` resolves relative paths (data/raw, ...)
// against the repository root, not this module's directory.
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }

dependencies {
    // TODO: re-add `implementation(project(":core:dictionary"))` when the binary writer lands;
    // the count pipeline is deliberately independent of it.
    implementation("org.apache.commons:commons-compress:1.27.1") // bzip2 decompression
}

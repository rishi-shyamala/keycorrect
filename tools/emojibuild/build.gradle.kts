plugins {
    id("keycorrect.jvm.library")
    application
}

application { mainClass.set("dev.keycorrect.tools.emojibuild.MainKt") }

// `./gradlew :tools:emojibuild:run --args="..."` resolves relative paths (data/raw, ...) against
// the repository root, not this module's directory.
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }

dependencies {
    implementation(project(":core:emoji")) // EmojiCatalog + EmojiCatalogWriter
}

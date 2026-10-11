plugins { id("keycorrect.jvm.library") }

dependencies {
    api(project(":core:engine-api"))
    // Part of the public API: ClassicEngine takes a Dictionary.
    api(project(":core:dictionary"))
    // Part of the public API: ClassicEngine takes a Personalization provider (Phase 3).
    api(project(":core:learning"))

    testImplementation(project(":core:layout"))
}

plugins { id("keycorrect.jvm.library") }

dependencies {
    api(project(":core:engine-api"))
    // Part of the public API: ClassicEngine takes a Dictionary.
    api(project(":core:dictionary"))

    testImplementation(project(":core:layout"))
}

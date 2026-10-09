plugins { id("keycorrect.jvm.library") }

dependencies {
    api(project(":core:engine-api"))
    implementation(project(":core:dictionary"))
}

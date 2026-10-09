plugins {
    id("keycorrect.jvm.library")
    application
}

application { mainClass.set("dev.keycorrect.tools.eval.MainKt") }

dependencies {
    implementation(project(":core:decoder"))
    implementation(project(":core:dictionary"))
    implementation(project(":core:layout"))
}

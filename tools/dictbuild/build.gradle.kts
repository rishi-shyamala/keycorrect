plugins {
    id("keycorrect.jvm.library")
    application
}

application { mainClass.set("dev.keycorrect.tools.dictbuild.MainKt") }

dependencies { implementation(project(":core:dictionary")) }

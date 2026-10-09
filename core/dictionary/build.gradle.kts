plugins { id("keycorrect.jvm.library") }

tasks.withType<Test>().configureEach { maxHeapSize = "2g" }

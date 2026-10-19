pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "keycorrect"

include(":app")

include(":benchmark")

include(":core:clipboard")

include(":core:data")

include(":core:decoder")

include(":core:designsystem")

include(":core:dictionary")

include(":core:emoji")

include(":core:engine-api")

include(":core:engine-sdk")

include(":core:ime")

include(":core:input")

include(":core:layout")

include(":core:llama")

include(":core:learning")

include(":core:packs")

include(":core:settings")

include(":core:transfer")

include(":core:voice")

include(":feature:keyboard-ui")

include(":feature:voice-sherpa")

include(":tools:dictbuild")

include(":tools:emojibuild")

include(":tools:eval")

include(":voicepack")

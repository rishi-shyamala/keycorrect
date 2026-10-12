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

include(":core:data")

include(":core:decoder")

include(":core:designsystem")

include(":core:dictionary")

include(":core:emoji")

include(":core:engine-api")

include(":core:ime")

include(":core:input")

include(":core:layout")

include(":core:learning")

include(":core:settings")

include(":feature:keyboard-ui")

include(":tools:dictbuild")

include(":tools:emojibuild")

include(":tools:eval")

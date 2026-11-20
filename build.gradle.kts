plugins {
    alias(libs.plugins.spotless)
}

// Formatted files: everything except build outputs, agent worktrees and the downloaded sources
// under data/ (a third-party tree with broken symlinks that must stay unmodified).
fun sourceTree(pattern: String) =
    fileTree(rootDir) {
        include(pattern)
        exclude("**/build/**", ".claude/**", "data/**")
    }

spotless {
    kotlin {
        target(sourceTree("**/*.kt"))
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    kotlinGradle {
        target(sourceTree("**/*.gradle.kts"))
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
}

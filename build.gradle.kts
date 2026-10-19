plugins {
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", ".claude/**")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", ".claude/**")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
}

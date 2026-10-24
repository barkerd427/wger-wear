// THROWAWAY spike build (see docs/spike/REPORT.md). Separate from the root build on purpose:
// it uses the same toolchain as the wger Flutter app (AGP 9.2.1 / Gradle 9.6.1 / Kotlin 2.3.20).
pluginManagement {
    repositories {
        google()
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
rootProject.name = "wger-wear-spike"
include(":shared", ":watch", ":phone")

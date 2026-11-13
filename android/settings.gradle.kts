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

rootProject.name = "MiBot"

// `core` is plain Kotlin (how the app talks to the hub) and can also be built by itself: see core/settings.gradle.kts.
include(":core")
include(":app")

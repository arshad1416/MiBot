// `core` is plain Kotlin, so it can be built and tested by itself on a machine without the Android SDK:
//
//   ./gradlew -p core test
//
// As part of the app it is the `:core` module of ../settings.gradle.kts, and this file is not read.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "mibot-core"

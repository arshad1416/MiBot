plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    // The test against a running hub (HubLiveTest) only runs when it is told where the hub is.
    providers.gradleProperty("hubUrl").orNull?.let { systemProperty("mibot.hubUrl", it) }
    providers.gradleProperty("hubCode").orNull?.let { systemProperty("mibot.hubCode", it) }
}

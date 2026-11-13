import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val qaBuild = providers.gradleProperty("mibotQa").orNull == "true"

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        if (qaBuild) variant.enable = false // QA must never produce a production-package APK.
    }
}

android {
    namespace = "app.mibot"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.mibot"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "QA_MODE", qaBuild.toString())
    }

    if (qaBuild) sourceSets.getByName("debug").java.srcDir("src/qa/kotlin")

    buildTypes {
        debug {
            if (qaBuild) {
                applicationIdSuffix = ".qa"
                versionNameSuffix = "-qa"
                resValue("string", "app_name", "MiBot QA")
            }
        }
        release {
            isMinifyEnabled = false
            // Signed with the debug key so a release build installs on your own phone without a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    testImplementation(libs.junit)
    implementation(project(":core"))
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.code.scanner)
}

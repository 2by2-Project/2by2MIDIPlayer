import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    androidTarget {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
    }
    // Library target only. The PC application and its UI will be added separately.
    jvm("desktop") {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
    }
    sourceSets {
        // Platform-neutral JVM audio lifecycle code, shared by JNI (Android) and JNA (desktop).
        androidMain { kotlin.srcDir("src/jvmMain/kotlin") }
        named("desktopMain") { kotlin.srcDir("src/jvmMain/kotlin") }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material)
            implementation(compose.components.resources)
            implementation("dev.atsushieno:ktmidi:0.11.2")
            api(libs.compose.material3)
            implementation(libs.compose.material.icons)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // Reuse the shipped MIDI corpus without copying it into the shared library.
        val desktopTest by getting {
            resources.srcDir("../app/src/main/assets")
        }
    }
}

compose.resources { packageOfResClass = "jp.project2by2.musicplayer.resources" }

android {
    namespace = "jp.project2by2.musicplayer.shared"
    compileSdk = 36
    defaultConfig.minSdk = 24
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

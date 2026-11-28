plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// iOS host: platform services (BASS, files, audio session) around the shared Compose screens.
// The Xcode project in xcode/ embeds this static framework through embedAndSignAppleFrameworkForXcode.
val bassHeaders = rootProject.layout.projectDirectory.dir("proprietary/ios")
// cinterop needs Apple's toolchain. Elsewhere a stub engine with the same API lets the iOS Kotlin code
// be type-checked: ./gradlew :iosApp:compileKotlinIosArm64 -Pkotlin.native.enableKlibsCrossCompilation=true
val appleHost = System.getProperty("os.name").startsWith("Mac")

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        if (appleHost) target.compilations.getByName("main").cinterops.create("bass") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/bass.def"))
            includeDirs(bassHeaders)
        }
        target.binaries.framework {
            baseName = "PlayerKit"
            isStatic = true
        }
    }
    sourceSets {
        iosMain { kotlin.srcDir(if (appleHost) "src/bass/kotlin" else "src/bassStub/kotlin") }
        iosMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons)
            implementation("dev.atsushieno:ktmidi:0.11.2")
        }
        iosTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Missing proprietary files fail early with a clear message rather than inside cinterop.
tasks.matching { it.name.startsWith("cinteropBass") }.configureEach {
    doFirst {
        listOf("bass.h", "bassmidi.h").forEach {
            check(bassHeaders.file(it).asFile.isFile) { "Missing proprietary/ios/$it (see proprietary/README.md)" }
        }
    }
}

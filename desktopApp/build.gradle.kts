import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.filekit.dialogs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("net.java.dev.jna:jna:5.17.0")
    testImplementation(kotlin("test"))
    // Exercise FileKit's actual XDG response parser (a runtime-only dependency in FileKit).
    testImplementation("com.github.hypfvieh:dbus-java-core:5.2.0")
}

compose.desktop {
    application {
        mainClass = "jp.project2by2.musicplayer.desktop.MainKt"
        providers.gradleProperty("packagingJavaHome").orNull?.let { javaHome = it }
        nativeDistributions {
            // Build a fresh verification image while another image is running.
            providers.gradleProperty("desktopOutputDir").orNull?.let { outputBaseDir.set(file(it)) }
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "2by2MusicPlayer"
            // Native installers use three components; Android keeps the shared display version.
            packageVersion = providers.gradleProperty("appVersion").get()
                .split('.').let { parts ->
                    require(parts.size in 2..3 && parts.all { it.toIntOrNull() != null }) {
                        "appVersion must be major.minor or major.minor.patch"
                    }
                    (parts + List(3 - parts.size) { "0" }).joinToString(".")
                }
            description = "2by2 MIDI Player"
            fileAssociation(mimeType = "audio/midi", extension = "mid", description = "MIDI File")
            fileAssociation(mimeType = "audio/midi", extension = "midi", description = "MIDI File")
            windows { iconFile.set(project.file("icons/app-icon.ico")) }
            linux { iconFile.set(project.file("src/main/resources/app-icon.png")) }
            modules("java.desktop", "java.prefs", "jdk.unsupported", "jdk.charsets", "jdk.security.auth")
            appResourcesRootDir.set(layout.projectDirectory.dir("resources"))
        }
    }
}

val nativePlatform = if (System.getProperty("os.name").startsWith("Windows")) "win-x64" else "linux-x64"
tasks.withType<JavaExec>().configureEach {
    systemProperty("bass.native.dir", rootProject.file("proprietary").absolutePath)
    systemProperty("midi.demo.dir", rootProject.file("app/src/main/assets/demo").absolutePath)
}
tasks.test {
    systemProperty("bass.native.dir", rootProject.file("proprietary").absolutePath)
    systemProperty("midi.demo.dir", rootProject.file("app/src/main/assets/demo").absolutePath)
    systemProperty("native.fixture.dir", layout.buildDirectory.dir("native-fixtures").get().asFile.absolutePath)
}

// Compose copies these resources alongside the installed application, outside its jars.
val stageNativeLibraries by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("proprietary/$nativePlatform"))
    into(layout.projectDirectory.dir("resources/common/proprietary/$nativePlatform"))
    doFirst {
        val names = if (nativePlatform == "win-x64") listOf("bass.dll", "bassmidi.dll") else listOf("libbass.so", "libbassmidi.so")
        names.forEach { check(rootProject.file("proprietary/$nativePlatform/$it").isFile) { "Missing proprietary/$nativePlatform/$it" } }
    }
}
val stageDemoMidi by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("app/src/main/assets/demo"))
    into(layout.projectDirectory.dir("resources/common/demo"))
    include("*.MID", "*.mid", "*.midi")
}
tasks.matching { it.name == "createDistributable" || it.name.startsWith("prepareAppResources") }.configureEach {
    dependsOn(stageNativeLibraries, stageDemoMidi)
}

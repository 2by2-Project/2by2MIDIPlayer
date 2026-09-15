import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.util.zip.ZipFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = if (System.getProperty("os.name").startsWith("Windows")) "2by2MIDIPlayer" else "project2by2-midiplayer"
            // Native installers use three components; Android keeps the shared display version.
            packageVersion = providers.gradleProperty("appVersion").get()
                .split('.').let { parts ->
                    require(parts.size in 2..3 && parts.all { it.toIntOrNull() != null }) {
                        "appVersion must be major.minor or major.minor.patch"
                    }
                    (parts + List(3 - parts.size) { "0" }).joinToString(".")
                }
            description = "2by2 MIDI Player"
            windows { iconFile.set(project.file("icons/app-icon.ico")) }
            linux {
                packageName = "project2by2-midiplayer"
                iconFile.set(project.file("src/main/resources/app-icon.png"))
                shortcut = true
            }
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

// Linux installer integration restored from 7914e89.
val stageLinuxNotices by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory) { include("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md") }
    into(layout.projectDirectory.dir("resources/common/licenses/project"))
}
// Remove generated helpers left by earlier builds so incremental packaging cannot bundle them.
val removeLegacyLinuxIntegration by tasks.registering(Delete::class) {
    delete(layout.projectDirectory.dir("resources/linux/integration"))
}
val linuxMenuIconName = providers.provider {
    val bytes = file("src/main/resources/app-icon.png").readBytes()
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }.take(16)
    "2by2-midi-player-$digest.png"
}
val stageLinuxMenuIcon by tasks.registering(Sync::class) {
    from(layout.projectDirectory.file("src/main/resources/app-icon.png"))
    rename { linuxMenuIconName.get() }
    into(layout.projectDirectory.dir("resources/linux/branding"))
}
if (nativePlatform == "linux-x64") {
    tasks.matching { it.name == "createDistributable" || it.name.startsWith("prepareAppResources") }.configureEach {
        dependsOn(stageLinuxNotices, removeLegacyLinuxIntegration, stageLinuxMenuIcon)
    }
    tasks.matching { it.name == "createDistributable" }.configureEach {
        inputs.dir(layout.projectDirectory.dir("resources/linux/branding"))
    }
    val installerSettings = providers.provider { tasks.getByName("packageDeb") as AbstractJPackageTask }
    val imageSettings = providers.provider { tasks.getByName("createDistributable") as AbstractJPackageTask }
    tasks.register("packageInstallers") {
        group = "distribution"
        description = "Build Linux DEB and RPM installers."
        val vendor = "2by2 Project"
        val maintainerEmail = "support@project2by2.jp"
        val formats = providers.gradleProperty("linuxInstallerFormats").orElse("deb,rpm")
        inputs.property("linuxInstallerFormats", formats)
        dependsOn("createDistributable")
        inputs.property("vendor", vendor)
        inputs.property("maintainerEmail", maintainerEmail)
        inputs.dir(layout.projectDirectory.dir("installer/linux"))
        inputs.file(layout.projectDirectory.file("src/main/resources/app-icon.png"))
        inputs.dir(imageSettings.flatMap { it.destinationDir })
        inputs.property("appVersion", providers.gradleProperty("appVersion"))
        inputs.property("packagingJavaHome", installerSettings.flatMap { it.javaHome })
        inputs.property("packageVersion", installerSettings.flatMap { it.packageVersion })
        inputs.property("packageName", installerSettings.flatMap { it.packageName })
        outputs.dir(layout.buildDirectory.dir("distributions"))
        doLast {
            val settings = installerSettings.get()
            val packagingHome = file(settings.javaHome.get())
            val appName = settings.packageName.get()
            val image = imageSettings.get().destinationDir.get().asFile.resolve(appName)
            val resourceDir = temporaryDir.resolve("resources").apply { mkdirs() }
            // A content-specific path avoids reusing a cached default Java launcher icon.
            val menuIconPath = "/opt/${settings.linuxPackageName.get()}/lib/app/resources/branding/${linuxMenuIconName.get()}"
            resourceDir.resolve("$appName.desktop").writeText(
                file("installer/linux/project2by2-midiplayer.desktop").readText()
                    .replace("APPLICATION_MENU_ICON", menuIconPath)
            )
            // Preserve the requested distribution description without multiline desktop comments.
            val descriptionParts = file("installer/linux/description-ja.txt")
                .readText(Charsets.UTF_8).trimEnd().split("\n\n", limit = 2)
            check(descriptionParts.size == 2) { "Expected a synopsis and a description paragraph" }
            val (summary, body) = descriptionParts
            ZipFile(packagingHome.resolve("jmods/jdk.jpackage.jmod")).use { zip ->
                fun template(name: String) = zip.getInputStream(requireNotNull(zip.getEntry("classes/jdk/jpackage/internal/resources/$name"))).bufferedReader().use { it.readText() }
                resourceDir.resolve("control").writeText(template("template.control").replace("APPLICATION_DESCRIPTION", "$summary\n .\n $body"))
                resourceDir.resolve("$appName.spec").writeText(template("template.spec")
                    .replace("APPLICATION_SUMMARY", summary)
                    .replace("APPLICATION_DESCRIPTION", "$summary\n\n$body"))
            }
            val output = layout.buildDirectory.dir("distributions").get().asFile.apply { mkdirs() }
            val requestedFormats = formats.get().split(',').map { it.trim() }.distinct()
            require(requestedFormats.isNotEmpty() && requestedFormats.all { it in listOf("deb", "rpm") }) {
                "linuxInstallerFormats must be deb, rpm, or deb,rpm"
            }
            for (format in requestedFormats) {
                val destination = Files.createTempDirectory(temporaryDir.toPath(), "$format-").toFile()
                val args = mutableListOf(
                    packagingHome.resolve("bin/jpackage").absolutePath,
                    "--type", format, "--app-image", image.absolutePath,
                    "--name", appName, "--app-version", settings.packageVersion.get(),
                    "--description", summary,
                    "--vendor", vendor,
                    "--icon", file("src/main/resources/app-icon.png").absolutePath,
                    "--resource-dir", resourceDir.absolutePath, "--dest", destination.absolutePath, "--verbose",
                )
                args.addAll(listOf("--linux-package-name", settings.linuxPackageName.get(), "--linux-shortcut", "--install-dir", "/opt"))
                if (format == "deb") {
                    args.addAll(listOf("--linux-deb-maintainer", maintainerEmail))
                }
                project.exec { commandLine(args) }
                val generated = destination.listFiles()!!.single { it.extension == format }
                val artifact = output.resolve("2by2MIDIPlayer-v${providers.gradleProperty("appVersion").get()}.$format")
                Files.move(generated.toPath(), artifact.toPath(), StandardCopyOption.REPLACE_EXISTING)
                destination.delete()
                logger.lifecycle("Installer: ${artifact.absolutePath}")
            }
        }
    }
}

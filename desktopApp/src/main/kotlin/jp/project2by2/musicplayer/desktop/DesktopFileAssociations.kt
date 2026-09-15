package jp.project2by2.musicplayer.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/** User-scoped registration, invoked only by the Settings button. Never writes Windows UserChoice. */
internal class DesktopFileAssociations(
    private val os: String = System.getProperty("os.name"),
    private val launcher: File? = System.getProperty("jpackage.app-path")?.let(::File),
    private val dataHome: File = System.getenv("XDG_DATA_HOME")?.takeIf { File(it).isAbsolute }?.let(::File)
        ?: File(System.getProperty("user.home"), ".local/share"),
    private val run: (List<String>) -> String = ::runAssociationCommand,
    private val openSettings: (URI) -> Unit = { Desktop.getDesktop().browse(it) },
    private val notifyWindowsShell: () -> Unit = ::notifyWindowsAssociations,
    private val desktop: String = System.getenv("XDG_CURRENT_DESKTOP").orEmpty(),
) {
    fun configure(): String {
        val executable = launcher?.takeIf { it.isFile && it.isAbsolute }
            ?: return "settings_association_unavailable"
        return when {
            os.startsWith("Windows") -> {
                registerWindows(executable)
                notifyWindowsShell()
                openSettings(URI("ms-settings:defaultapps?registeredAppUser=2by2%20MIDI%20Player"))
                "settings_association_windows"
            }
            os.startsWith("Linux") -> {
                registerLinux(executable)
                "settings_association_linux"
            }
            else -> "settings_association_unavailable"
        }
    }

    private fun registerWindows(executable: File) {
        require(executable.extension.equals("exe", ignoreCase = true))
        val classes = "HKCU\\Software\\Classes"
        val progId = "Project2by2.MIDI"
        val capabilities = "Software\\Project2by2\\MIDIPlayer\\Capabilities"
        fun value(key: String, name: String?, data: String, type: String = "REG_SZ") {
            run(listOf("reg.exe", "add", key) + (name?.let { listOf("/v", it) } ?: listOf("/ve")) +
                listOf("/t", type, "/d", data, "/f"))
        }
        value("$classes\\$progId", null, "MIDI file")
        value("$classes\\$progId\\DefaultIcon", null, "\"${executable.path}\",0")
        value("$classes\\$progId\\shell\\open\\command", null, "\"${executable.path}\" \"%1\"")
        value("HKCU\\$capabilities", "ApplicationName", APP_NAME)
        value("HKCU\\$capabilities", "ApplicationDescription", "Play MIDI files with 2by2 MIDI Player")
        for (extension in listOf(".mid", ".midi")) {
            value("HKCU\\$capabilities\\FileAssociations", extension, progId)
            value("$classes\\$extension\\OpenWithProgids", progId, "", "REG_NONE")
        }
        value("HKCU\\Software\\RegisteredApplications", APP_NAME, capabilities)
    }

    private fun registerLinux(executable: File) {
        require(executable.canExecute())
        val applications = File(dataHome, "applications")
        check(applications.isDirectory || applications.mkdirs())
        val icon = DesktopFileAssociations::class.java.getResourceAsStream("/app-icon.png")?.use { input ->
            File(dataHome, "icons/project2by2-midiplayer.png").also { file ->
                check(file.parentFile.isDirectory || file.parentFile.mkdirs())
                file.outputStream().use { input.copyTo(it) }
            }
        }
        File(applications, DESKTOP_ID).writeText(linuxDesktopEntry(executable, icon))
        // xdg-mime works without update-desktop-database; refresh the optional cache when available.
        try { run(listOf("update-desktop-database", applications.path)) } catch (_: java.io.IOException) { }
        run(listOf("xdg-mime", "default", DESKTOP_ID) + MIME_TYPES)
        check(linuxDefaultsMatch()) {
            "The desktop environment did not accept the MIDI defaults"
        }
    }

    internal fun linuxDefaultsMatch(): Boolean = MIME_TYPES.all { mime ->
        // xdg-utils 1.1.3 cannot resolve quoted Exec paths in query default. GIO is the
        // authority used by GNOME/Cinnamon file managers and correctly parses those paths.
        val useGio = desktop.split(':').any {
            it.equals("GNOME", true) || it.equals("Cinnamon", true) || it.equals("X-Cinnamon", true)
        }
        val gioDefault = if (useGio) {
            try {
                val firstLine = run(listOf("gio", "mime", mime)).lineSequence().firstOrNull().orEmpty()
                // Only the default line counts; the player can also occur in the candidate lists.
                firstLine.startsWith("Default application for ") &&
                    firstLine.substringAfter(": ", "").trim() == DESKTOP_ID
            } catch (_: java.io.IOException) {
                null // Older/minimal installations may not provide the gio executable.
            }
        } else null
        gioDefault ?: (run(listOf("xdg-mime", "query", "default", mime)).trim() == DESKTOP_ID)
    }

    companion object {
        const val APP_NAME = "2by2 MIDI Player"
        const val DESKTOP_ID = "project2by2-midiplayer.desktop"
        val MIME_TYPES = listOf("audio/midi", "audio/x-midi", "audio/mid", "audio/x-mid")

        internal fun linuxDesktopEntry(executable: File, icon: File? = null): String {
            // Exec quoting is parsed after desktop-entry string escaping. Never invoke a shell.
            require(executable.path.none { it == '\n' || it == '\r' || it == '\u0000' })
            val quoted = buildString {
                append('"')
                for (char in executable.path) {
                    if (char in "\\\"`$") append('\\')
                    append(if (char == '%') "%%" else char.toString())
                }
                append('"')
            }.replace("\\", "\\\\")
            return """
                [Desktop Entry]
                Type=Application
                Name=$APP_NAME
                Exec=$quoted %F
                ${icon?.let { "Icon=" + it.path.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r") } ?: ""}
                Terminal=false
                Categories=AudioVideo;Audio;Player;
                MimeType=${MIME_TYPES.joinToString(";")};
            """.trimIndent() + "\n"
        }
    }
}

private fun runAssociationCommand(command: List<String>): String {
    val output = File.createTempFile("midi-association-", ".log")
    try {
        val process = ProcessBuilder(command).apply {
            // Keep CLI diagnostics parseable regardless of the application's display language.
            environment()["LC_ALL"] = "C"
            environment()["LANGUAGE"] = "C"
        }.redirectErrorStream(true).redirectOutput(output).start()
        try {
            check(process.waitFor(15, TimeUnit.SECONDS)) { "File association command timed out" }
            check(process.exitValue() == 0) { "File association command failed: ${command.first()}" }
            return output.bufferedReader().use { it.readText() }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    } finally {
        output.delete()
    }
}

private interface AssociationShell : StdCallLibrary {
    fun SHChangeNotify(eventId: Int, flags: Int, item1: Pointer?, item2: Pointer?)
}

private fun notifyWindowsAssociations() {
    // SHCNE_ASSOCCHANGED / SHCNF_IDLIST: refresh association caches before opening Settings.
    Native.load("shell32", AssociationShell::class.java).SHChangeNotify(0x08000000, 0, null, null)
}

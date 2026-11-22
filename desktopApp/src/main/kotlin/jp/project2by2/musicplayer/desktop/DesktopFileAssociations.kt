package jp.project2by2.musicplayer.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * The installer (MSI / DEB / RPM) registers the player as a MIDI handler candidate at its
 * installed location, and the user chooses the default in the OS. This Settings action only
 * confirms that the running launcher is that installed one, removes registrations written by
 * older versions, and then opens or applies the OS default-app choice. It never writes the
 * launcher's own path, so re-running the installer cannot leave stale registrations behind.
 * Windows UserChoice is never written.
 */
internal class DesktopFileAssociations(
    private val os: String = System.getProperty("os.name"),
    private val launcher: File? = System.getProperty("jpackage.app-path")?.let(::File),
    private val dataHome: File = System.getenv("XDG_DATA_HOME")?.takeIf { File(it).isAbsolute }?.let(::File)
        ?: File(System.getProperty("user.home"), ".local/share"),
    private val dataDirs: List<File> = (System.getenv("XDG_DATA_DIRS")?.takeIf { it.isNotBlank() }
        ?: "/usr/local/share:/usr/share").split(':').map(::File).filter { it.isAbsolute },
    private val run: (List<String>) -> String = ::runAssociationCommand,
    private val openSettings: (URI) -> Unit = { Desktop.getDesktop().browse(it) },
    private val notifyWindowsShell: () -> Unit = ::notifyWindowsAssociations,
    /** The installer's open command for [PROG_ID] in HKLM, or null when it is not registered. */
    private val readInstalledOpenCommand: () -> String? = ::readMachineOpenCommand,
    private val desktop: String = System.getenv("XDG_CURRENT_DESKTOP").orEmpty(),
) {
    fun configure(): String {
        val executable = launcher?.takeIf { it.isFile && it.isAbsolute }
            ?: return UNAVAILABLE
        return when {
            os.startsWith("Windows") -> configureWindows(executable)
            os.startsWith("Linux") -> configureLinux(executable)
            else -> UNAVAILABLE
        }
    }

    private fun configureWindows(executable: File): String {
        val command = readInstalledOpenCommand() ?: return UNAVAILABLE
        if (!samePath(commandExecutable(command), executable, ignoreCase = true)) return UNAVAILABLE
        removeLegacyWindowsRegistration()
        notifyWindowsShell()
        openSettings(URI("ms-settings:defaultapps?registeredAppMachine=2by2%20MIDI%20Player"))
        return "settings_association_windows"
    }

    /** Older versions registered the running launcher in HKCU, which takes precedence over HKLM. */
    private fun removeLegacyWindowsRegistration() {
        val deletions = listOf(
            listOf("HKCU\\Software\\Classes\\$PROG_ID"),
            // Only Capabilities: the installer keeps its shortcut key paths under MIDIPlayer\\Installer.
            listOf("HKCU\\Software\\Project2by2\\MIDIPlayer\\Capabilities"),
            listOf("HKCU\\Software\\RegisteredApplications", "/v", APP_NAME),
            listOf("HKCU\\Software\\Classes\\.mid\\OpenWithProgids", "/v", PROG_ID),
            listOf("HKCU\\Software\\Classes\\.midi\\OpenWithProgids", "/v", PROG_ID),
        )
        for (target in deletions) {
            try {
                run(listOf("reg.exe", "delete") + target + listOf("/f", "/reg:64"))
            } catch (_: IllegalStateException) {
                // Nothing to remove: this user never used the old registration.
            }
        }
    }

    private fun configureLinux(executable: File): String {
        val entry = dataDirs.map { File(it, "applications/$INSTALLED_DESKTOP_ID") }.firstOrNull { it.isFile }
            ?: return UNAVAILABLE
        val entryExecutable = desktopEntryExecutable(entry.readText()) ?: return UNAVAILABLE
        if (!samePath(entryExecutable, executable, ignoreCase = false)) return UNAVAILABLE
        removeLegacyLinuxEntry()
        run(listOf("xdg-mime", "default", INSTALLED_DESKTOP_ID) + MIME_TYPES)
        check(linuxDefaultsMatch()) {
            "The desktop environment did not accept the MIDI defaults"
        }
        return "settings_association_linux"
    }

    /** Older versions wrote a second, user-level menu entry for the running launcher. */
    private fun removeLegacyLinuxEntry() {
        val applications = File(dataHome, "applications")
        val removed = File(applications, LEGACY_DESKTOP_ID).delete()
        File(dataHome, "icons/project2by2-midiplayer.png").delete()
        if (removed) {
            // Refresh the optional user cache when available; xdg-mime does not need it.
            try { run(listOf("update-desktop-database", applications.path)) } catch (_: java.io.IOException) { }
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
                    firstLine.substringAfter(": ", "").trim() == INSTALLED_DESKTOP_ID
            } catch (_: java.io.IOException) {
                null // Older/minimal installations may not provide the gio executable.
            }
        } else null
        gioDefault ?: (run(listOf("xdg-mime", "query", "default", mime)).trim() == INSTALLED_DESKTOP_ID)
    }

    companion object {
        const val APP_NAME = "2by2 MIDI Player"
        private const val UNAVAILABLE = "settings_association_unavailable"
        /** Registered by desktopApp/installer/windows/midi-associations.xml. */
        const val PROG_ID = "Project2by2.MIDI"
        internal const val MACHINE_OPEN_COMMAND = "Software\\Classes\\$PROG_ID\\shell\\open\\command"
        /** The entry the DEB/RPM installs with xdg-desktop-menu (vendor-prefixed by jpackage). */
        const val INSTALLED_DESKTOP_ID = "project2by2-midiplayer-project2by2-midiplayer.desktop"
        private const val LEGACY_DESKTOP_ID = "project2by2-midiplayer.desktop"
        val MIME_TYPES = listOf("audio/midi", "audio/x-midi", "audio/mid", "audio/x-mid")

        /** The executable of a command such as `"C:\...\2by2MIDIPlayer.exe" "%1"`. */
        internal fun commandExecutable(command: String): String =
            if (command.startsWith('"')) command.substring(1).substringBefore('"')
            else command.substringBefore(' ')

        /** The program of a desktop entry's Exec key, unquoting per the Desktop Entry spec. */
        internal fun desktopEntryExecutable(entry: String): String? {
            val exec = entry.lineSequence().map { it.trim() }
                .firstOrNull { it.startsWith("Exec=") }?.removePrefix("Exec=")?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return null
            if (!exec.startsWith('"')) return exec.substringBefore(' ')
            val program = StringBuilder()
            var index = 1
            while (index < exec.length) {
                val char = exec[index]
                when {
                    char == '"' -> return program.toString()
                    char == '\\' && index + 1 < exec.length -> { program.append(exec[index + 1]); index++ }
                    else -> program.append(char)
                }
                index++
            }
            return null // Unterminated quote.
        }

        private fun samePath(path: String, executable: File, ignoreCase: Boolean): Boolean =
            File(path).absoluteFile.normalize().path.equals(executable.absoluteFile.normalize().path, ignoreCase)
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

private interface AssociationRegistry : StdCallLibrary {
    fun RegGetValueW(key: Pointer, subKey: WString, value: WString?, flags: Int, type: Pointer?,
                     data: Pointer?, size: IntByReference): Int
}

/**
 * Reads the default value of the installer's open command from the 64-bit HKLM view.
 * The registry API is used instead of reg.exe, whose console output uses the OEM code page
 * and would corrupt installation paths containing non-ASCII characters.
 */
private fun readMachineOpenCommand(): String? {
    val registry = Native.load("advapi32", AssociationRegistry::class.java)
    val localMachine = Pointer(0x80000002L.toInt().toLong()) // HKEY_LOCAL_MACHINE, sign-extended
    val flags = 0x00000002 or 0x00010000 // RRF_RT_REG_SZ | RRF_SUBKEY_WOW6464KEY
    val subKey = WString(DesktopFileAssociations.MACHINE_OPEN_COMMAND)
    val size = IntByReference()
    var status = registry.RegGetValueW(localMachine, subKey, null, flags, null, null, size)
    if (status == 2) return null // ERROR_FILE_NOT_FOUND: the installer has not registered the player.
    check(status == 0) { "Cannot read the installed MIDI registration ($status)" }
    val data = Memory(maxOf(size.value, 2).toLong())
    status = registry.RegGetValueW(localMachine, subKey, null, flags, null, data, size)
    check(status == 0) { "Cannot read the installed MIDI registration ($status)" }
    return data.getWideString(0).takeIf { it.isNotEmpty() }
}

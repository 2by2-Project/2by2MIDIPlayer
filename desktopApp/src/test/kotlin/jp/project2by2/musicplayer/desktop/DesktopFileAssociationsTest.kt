package jp.project2by2.musicplayer.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DesktopFileAssociationsTest {
    private val installedId = DesktopFileAssociations.INSTALLED_DESKTOP_ID

    private fun fixture(block: (File, File) -> Unit) {
        val root = Files.createTempDirectory("association-test-").toFile()
        try {
            val executable = File(root, "player with spaces.exe").apply { writeText(""); setExecutable(true) }
            block(root, executable)
        } finally { root.deleteRecursively() }
    }

    /** A system data directory holding the entry the DEB/RPM installs. */
    private fun installEntry(root: File, exec: String): File = File(root, "system").also { dataDir ->
        File(dataDir, "applications").mkdirs()
        File(dataDir, "applications/$installedId").writeText(
            "[Desktop Entry]\nType=Application\nName=2by2 MIDI Player\nExec=$exec\nMimeType=audio/midi;\n")
    }

    @Test fun developmentLaunchDoesNotTouchTheSystem() {
        assertEquals("settings_association_unavailable", DesktopFileAssociations(
            launcher = null, run = { error("Must not run") }, openSettings = { error("Must not open") },
            readInstalledOpenCommand = { error("Must not read") }
        ).configure())
    }

    @Test fun windowsWithoutInstallerRegistrationIsUnavailable() = fixture { root, exe ->
        assertEquals("settings_association_unavailable", DesktopFileAssociations("Windows 11", exe, root,
            run = { error("Must not run") }, openSettings = { fail("Must not open") },
            readInstalledOpenCommand = { null }).configure())
    }

    @Test fun windowsCopyOutsideTheInstalledLocationIsUnavailable() = fixture { root, exe ->
        assertEquals("settings_association_unavailable", DesktopFileAssociations("Windows 11", exe, root,
            run = { error("Must not run") }, openSettings = { fail("Must not open") },
            readInstalledOpenCommand = { "\"C:\\Program Files\\2by2MIDIPlayer\\2by2MIDIPlayer.exe\" \"%1\"" }
        ).configure())
    }

    @Test fun windowsRemovesLegacyUserRegistrationThenOpensDefaultApps() = fixture { root, exe ->
        val commands = mutableListOf<List<String>>()
        var opened = false
        var notified = false
        val result = DesktopFileAssociations("Windows 11", exe, root,
            run = {
                commands += it
                // reg.exe fails when the old registration does not exist; that must not stop setup.
                error("The system was unable to find the specified registry key or value.")
            },
            openSettings = {
                assertEquals("ms-settings:defaultapps?registeredAppMachine=2by2%20MIDI%20Player", it.toString())
                assertTrue(notified)
                opened = true
            },
            notifyWindowsShell = { notified = true },
            // Windows paths are case-insensitive.
            readInstalledOpenCommand = { "\"${exe.path.uppercase()}\" \"%1\"" }).configure()
        assertEquals("settings_association_windows", result)
        assertTrue(opened)
        assertTrue(commands.isNotEmpty())
        assertTrue(commands.all { it.take(2) == listOf("reg.exe", "delete") && it[2].startsWith("HKCU\\") })
        assertTrue(commands.none { command -> command.any { "UserChoice" in it } })
        assertTrue(commands.contains(listOf("reg.exe", "delete", "HKCU\\Software\\Classes\\Project2by2.MIDI", "/f", "/reg:64")))
        // The installer's own HKCU key paths (MIDIPlayer\\Installer) must survive the cleanup.
        assertTrue(commands.none { it[2] == "HKCU\\Software\\Project2by2\\MIDIPlayer" })
        assertTrue(commands.contains(listOf("reg.exe", "delete", "HKCU\\Software\\Project2by2\\MIDIPlayer\\Capabilities", "/f", "/reg:64")))
        for (extension in listOf(".mid", ".midi")) {
            assertTrue(commands.any { it[2] == "HKCU\\Software\\Classes\\$extension\\OpenWithProgids" && "Project2by2.MIDI" in it })
        }
    }

    @Test fun commandExecutableHandlesQuotedAndPlainCommands() {
        assertEquals("C:\\Program Files\\2by2MIDIPlayer\\2by2MIDIPlayer.exe",
            DesktopFileAssociations.commandExecutable("\"C:\\Program Files\\2by2MIDIPlayer\\2by2MIDIPlayer.exe\" \"%1\""))
        assertEquals("C:\\Apps\\player.exe", DesktopFileAssociations.commandExecutable("C:\\Apps\\player.exe %1"))
    }

    @Test fun linuxUsesTheInstalledEntryAndRemovesTheLegacyOne() = fixture { root, exe ->
        val home = File(root, "home").apply { File(this, "applications").mkdirs(); File(this, "icons").mkdirs() }
        val legacy = File(home, "applications/project2by2-midiplayer.desktop").apply { writeText("[Desktop Entry]\n") }
        val legacyIcon = File(home, "icons/project2by2-midiplayer.png").apply { writeText("") }
        val dataDir = installEntry(root, "\"${exe.path}\" %F")
        val commands = mutableListOf<List<String>>()
        val result = DesktopFileAssociations("Linux", exe, home, listOf(File(root, "missing"), dataDir), desktop = "", run = {
            commands += it
            if (it.take(2) == listOf("xdg-mime", "query")) installedId else ""
        }).configure()
        assertEquals("settings_association_linux", result)
        assertFalse(legacy.exists())
        assertFalse(legacyIcon.exists())
        assertTrue(commands.contains(listOf("update-desktop-database", File(home, "applications").path)))
        assertTrue(commands.contains(listOf("xdg-mime", "default", installedId) + DesktopFileAssociations.MIME_TYPES))
        assertEquals(DesktopFileAssociations.MIME_TYPES.size, commands.count { it.take(2) == listOf("xdg-mime", "query") })
        // No user-level entry is written any more.
        assertEquals(emptyList(), File(home, "applications").list()!!.toList())
    }

    @Test fun linuxWithoutTheInstalledEntryIsUnavailable() = fixture { root, exe ->
        assertEquals("settings_association_unavailable", DesktopFileAssociations("Linux", exe, root,
            listOf(File(root, "missing")), desktop = "", run = { error("Must not run") }).configure())
    }

    @Test fun linuxEntryForAnotherLauncherIsUnavailable() = fixture { root, exe ->
        val dataDir = installEntry(root, "/opt/project2by2-midiplayer/bin/project2by2-midiplayer %F")
        assertEquals("settings_association_unavailable", DesktopFileAssociations("Linux", exe, root,
            listOf(dataDir), desktop = "", run = { error("Must not run") }).configure())
    }

    @Test fun linuxDoesNotReportSuccessWhenPolicyKeepsAnotherDefault() = fixture { root, exe ->
        val dataDir = installEntry(root, "\"${exe.path}\" %F")
        assertFailsWith<IllegalStateException> {
            DesktopFileAssociations("Linux", exe, root, listOf(dataDir), desktop = "",
                run = { "another-player.desktop" }).configure()
        }
    }

    @Test fun desktopEntryExecutableUnquotesExec() {
        assertEquals("/opt/project2by2-midiplayer/bin/project2by2-midiplayer",
            DesktopFileAssociations.desktopEntryExecutable("[Desktop Entry]\nExec=/opt/project2by2-midiplayer/bin/project2by2-midiplayer %F\n"))
        assertEquals("/tmp/a \"b\" \\c",
            DesktopFileAssociations.desktopEntryExecutable("Exec=\"/tmp/a \\\"b\\\" \\\\c\" %F"))
        assertNull(DesktopFileAssociations.desktopEntryExecutable("[Desktop Entry]\nName=x\n"))
        assertNull(DesktopFileAssociations.desktopEntryExecutable("Exec=\"/tmp/unterminated %F"))
    }

    @Test fun cinnamonUsesGioWhenXdgWouldReturnAnotherPlayer() {
        val queries = mutableListOf<List<String>>()
        val associations = DesktopFileAssociations(desktop = "X-Cinnamon", run = {
            queries += it
            when (it.first()) {
                "gio" -> "Default application for ?${it.last()}?: $installedId\nRegistered applications:\n\tother.desktop\n"
                else -> "other.desktop"
            }
        })
        assertTrue(associations.linuxDefaultsMatch())
        assertEquals(DesktopFileAssociations.MIME_TYPES.map { listOf("gio", "mime", it) }, queries)
    }

    @Test fun gioCandidatesDoNotCountAsTheDefaultEvenWhenXdgReportsSuccess() {
        for (firstLine in listOf("Default application for ?audio/midi?: other.desktop", "No default applications for ?audio/midi?")) {
            val associations = DesktopFileAssociations(desktop = "ubuntu:GNOME", run = {
                if (it.first() == "gio") "$firstLine\nRegistered applications:\n\t$installedId\n"
                else installedId
            })
            assertFalse(associations.linuxDefaultsMatch())
        }
    }

    @Test fun missingGioFallsBackToXdgButCommandFailuresDoNot() {
        val associations = DesktopFileAssociations(desktop = "Cinnamon", run = {
            if (it.first() == "gio") throw java.io.IOException("gio not installed")
            installedId
        })
        assertTrue(associations.linuxDefaultsMatch())
        assertFailsWith<IllegalStateException> {
            DesktopFileAssociations(desktop = "GNOME", run = {
                if (it.first() == "gio") error("gio failed")
                installedId
            }).linuxDefaultsMatch()
        }
    }

    @Test fun kdeKeepsItsOwnDefaultLookup() {
        assertTrue(DesktopFileAssociations(desktop = "KDE", run = {
            assertEquals(listOf("xdg-mime", "query", "default"), it.take(3))
            installedId
        }).linuxDefaultsMatch())
    }

    @Test fun gioChecksEveryMimeType() {
        assertFalse(DesktopFileAssociations(desktop = "X-Cinnamon", run = {
            val app = if (it.last() == "audio/x-mid") "other.desktop" else installedId
            "Default application for ?${it.last()}?: $app\n"
        }).linuxDefaultsMatch())
    }
}

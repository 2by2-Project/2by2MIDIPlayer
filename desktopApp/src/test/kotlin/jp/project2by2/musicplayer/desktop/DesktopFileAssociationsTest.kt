package jp.project2by2.musicplayer.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DesktopFileAssociationsTest {
    private fun fixture(block: (File, File) -> Unit) {
        val root = Files.createTempDirectory("association-test-").toFile()
        try {
            val executable = File(root, "player with spaces.exe").apply { writeText(""); setExecutable(true) }
            block(root, executable)
        } finally { root.deleteRecursively() }
    }

    @Test fun developmentLaunchDoesNotRegisterAnything() {
        assertEquals("settings_association_unavailable", DesktopFileAssociations(
            launcher = null, run = { error("Must not run") }, openSettings = { error("Must not open") }
        ).configure())
    }

    @Test fun windowsRegistersOnlyUserScopedCandidatesAndOpensSettingsLast() = fixture { root, exe ->
        val commands = mutableListOf<List<String>>()
        var opened = false
        var notified = false
        val result = DesktopFileAssociations("Windows 11", exe, root, run = { commands += it; "" },
            openSettings = {
                assertTrue(commands.last().contains("HKCU\\Software\\RegisteredApplications"))
                assertEquals("ms-settings:defaultapps?registeredAppUser=2by2%20MIDI%20Player", it.toString())
                assertTrue(notified)
                opened = true
            }, notifyWindowsShell = { notified = true }).configure()
        assertEquals("settings_association_windows", result)
        assertTrue(opened)
        assertTrue(commands.all { it[2].startsWith("HKCU\\") && it.none { arg -> "UserChoice" in arg } })
        assertTrue(commands.any { it.contains("\"${exe.path}\" \"%1\"") })
        for (extension in listOf(".mid", ".midi")) {
            assertTrue(commands.any { it.contains("HKCU\\Software\\Classes\\$extension\\OpenWithProgids") })
        }
    }

    @Test fun registrationFailureDoesNotOpenSettings() = fixture { root, exe ->
        assertFailsWith<IllegalStateException> {
            DesktopFileAssociations("Windows 11", exe, root, run = { error("Registry denied") },
                openSettings = { fail("Must not open") }).configure()
        }
    }

    @Test fun linuxRegistersLauncherAndVerifiesDefaults() = fixture { root, exe ->
        val commands = mutableListOf<List<String>>()
        val result = DesktopFileAssociations("Linux", exe, root, desktop = "", run = {
            commands += it
            assertTrue(File(root, "applications/${DesktopFileAssociations.DESKTOP_ID}").isFile)
            if (it.take(2) == listOf("xdg-mime", "query")) DesktopFileAssociations.DESKTOP_ID else ""
        }).configure()
        assertEquals("settings_association_linux", result)
        val entry = File(root, "applications/${DesktopFileAssociations.DESKTOP_ID}").readText()
        assertContains(entry, "Exec=\"${exe.path}\" %F")
        assertTrue(commands.contains(listOf("xdg-mime", "default", DesktopFileAssociations.DESKTOP_ID) + DesktopFileAssociations.MIME_TYPES))
        assertEquals(DesktopFileAssociations.MIME_TYPES.size, commands.count { it.take(2) == listOf("xdg-mime", "query") })
    }

    @Test fun linuxDoesNotReportSuccessWhenPolicyKeepsAnotherDefault() = fixture { root, exe ->
        assertFailsWith<IllegalStateException> {
            DesktopFileAssociations("Linux", exe, root, desktop = "", run = { "another-player.desktop" }).configure()
        }
    }

    @Test fun cinnamonUsesGioWhenXdgWouldReturnAnotherPlayer() {
        val queries = mutableListOf<List<String>>()
        val associations = DesktopFileAssociations(desktop = "X-Cinnamon", run = {
            queries += it
            when (it.first()) {
                "gio" -> "Default application for ?${it.last()}?: ${DesktopFileAssociations.DESKTOP_ID}\nRegistered applications:\n\tother.desktop\n"
                else -> "other.desktop"
            }
        })
        assertTrue(associations.linuxDefaultsMatch())
        assertEquals(DesktopFileAssociations.MIME_TYPES.map { listOf("gio", "mime", it) }, queries)
    }

    @Test fun gioCandidatesDoNotCountAsTheDefaultEvenWhenXdgReportsSuccess() {
        for (firstLine in listOf("Default application for ?audio/midi?: other.desktop", "No default applications for ?audio/midi?")) {
            val associations = DesktopFileAssociations(desktop = "ubuntu:GNOME", run = {
                if (it.first() == "gio") "$firstLine\nRegistered applications:\n\t${DesktopFileAssociations.DESKTOP_ID}\n"
                else DesktopFileAssociations.DESKTOP_ID
            })
            assertFalse(associations.linuxDefaultsMatch())
        }
    }

    @Test fun missingGioFallsBackToXdgButCommandFailuresDoNot() {
        val associations = DesktopFileAssociations(desktop = "Cinnamon", run = {
            if (it.first() == "gio") throw java.io.IOException("gio not installed")
            DesktopFileAssociations.DESKTOP_ID
        })
        assertTrue(associations.linuxDefaultsMatch())
        assertFailsWith<IllegalStateException> {
            DesktopFileAssociations(desktop = "GNOME", run = {
                if (it.first() == "gio") error("gio failed")
                DesktopFileAssociations.DESKTOP_ID
            }).linuxDefaultsMatch()
        }
    }

    @Test fun kdeKeepsItsOwnDefaultLookup() {
        assertTrue(DesktopFileAssociations(desktop = "KDE", run = {
            assertEquals(listOf("xdg-mime", "query", "default"), it.take(3))
            DesktopFileAssociations.DESKTOP_ID
        }).linuxDefaultsMatch())
    }

    @Test fun gioChecksEveryMimeType() {
        assertFalse(DesktopFileAssociations(desktop = "X-Cinnamon", run = {
            val app = if (it.last() == "audio/x-mid") "other.desktop" else DesktopFileAssociations.DESKTOP_ID
            "Default application for ?${it.last()}?: $app\n"
        }).linuxDefaultsMatch())
    }

    @Test fun linuxExecEscapesDesktopEntryAndExecSyntax() {
        val entry = DesktopFileAssociations.linuxDesktopEntry(File("/tmp/a $`\"\\% player"))
        assertContains(entry, "Exec=\"/tmp/a \\\\$\\\\`\\\\\"\\\\\\\\%% player\" %F")
        assertFailsWith<IllegalArgumentException> {
            DesktopFileAssociations.linuxDesktopEntry(File("/tmp/bad\nExec=other"))
        }
    }
}

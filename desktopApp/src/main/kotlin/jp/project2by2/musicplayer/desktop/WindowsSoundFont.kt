package jp.project2by2.musicplayer.desktop

import java.io.File

/** Resolve the OS-owned bank without copying it into the application distribution. */
fun windowsSoundFont(os: String = System.getProperty("os.name"), windowsDirectory: String? = System.getenv("SystemRoot")): File? {
    if (!os.startsWith("Windows", ignoreCase = true)) return null
    return File(windowsDirectory ?: "C:\\Windows", "System32/drivers/gm.dls")
}

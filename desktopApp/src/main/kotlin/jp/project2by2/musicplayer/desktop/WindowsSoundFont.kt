package jp.project2by2.musicplayer.desktop

import java.io.File
import jp.project2by2.musicplayer.platform.Platform
import jp.project2by2.musicplayer.platform.currentPlatform

/** Resolve the OS-owned bank without copying it into the application distribution. */
fun windowsSoundFont(platform: Platform = currentPlatform, windowsDirectory: String? = System.getenv("SystemRoot")): File? {
    if (!platform.isWindows) return null
    return File(windowsDirectory ?: "C:\\Windows", "System32/drivers/gm.dls")
}

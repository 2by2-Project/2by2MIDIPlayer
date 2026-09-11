package jp.project2by2.musicplayer.platform

actual val currentPlatform: Platform = detectDesktopPlatform(System.getProperty("os.name"))

internal fun detectDesktopPlatform(osName: String): Platform = when {
    osName.startsWith("Windows", ignoreCase = true) -> Platform.Windows
    osName.startsWith("Linux", ignoreCase = true) -> Platform.Linux
    else -> error("対応するOSはWindows/Linuxです: $osName")
}

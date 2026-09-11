package jp.project2by2.musicplayer.platform

/** Operating system identity, independent of window size and input devices. */
enum class Platform {
    Android, Windows, Linux;

    val isAndroid: Boolean get() = this == Android
    val isWindows: Boolean get() = this == Windows
    val isLinux: Boolean get() = this == Linux
    val isDesktop: Boolean get() = this == Windows || this == Linux
}

/** Available to both Composables and ordinary Kotlin code without a Context. */
expect val currentPlatform: Platform

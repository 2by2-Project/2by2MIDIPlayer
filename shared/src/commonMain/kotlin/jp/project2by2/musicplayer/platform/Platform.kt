package jp.project2by2.musicplayer.platform

/** Operating system identity, independent of window size and input devices. */
enum class Platform {
    Android, Windows, Linux, IOS;

    val isAndroid: Boolean get() = this == Android
    val isWindows: Boolean get() = this == Windows
    val isLinux: Boolean get() = this == Linux
    val isIos: Boolean get() = this == IOS
    val isDesktop: Boolean get() = this == Windows || this == Linux
    /** Phones and tablets: touch-first platforms whose files live in app or system storage. */
    val isMobile: Boolean get() = this == Android || this == IOS
}

/** Available to both Composables and ordinary Kotlin code without a Context. */
expect val currentPlatform: Platform

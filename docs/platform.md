# Platform access

Use the shared platform identity from Composables or ordinary Kotlin code:

```kotlin
import jp.project2by2.musicplayer.platform.Platform
import jp.project2by2.musicplayer.platform.currentPlatform

if (currentPlatform.isDesktop) {
    // Windows and Linux
}

when (currentPlatform) {
    Platform.Android -> Unit
    Platform.Windows -> Unit
    Platform.Linux -> Unit
}
```

`isAndroid`, `isWindows`, `isLinux`, and `isDesktop` are properties of `Platform`.
For example, `RecommendedSoundFontDialog` explicitly checks `currentPlatform.isWindows`
before showing the gm.dls card; its callback supplies the actual loading operation.
The read-only `currentPlatform` needs no Context, CompositionLocal, or initialization
by the application. Android supplies a fixed value; desktop resolves `os.name` once.
Unsupported desktop operating systems fail explicitly. Native audio separately
validates CPU architecture before loading its libraries.

Use this identity for OS-dependent decisions. Window layout remains based on available
size, and mouse/touch behavior should reflect input rather than OS identity.
Platform APIs stay in platform source sets: existing expect/actual helpers and
callbacks (file pickers, settings persistence, audio) remain the entry points for
those operations. An OS branch in commonMain does not make Android or Java APIs
available to common code.

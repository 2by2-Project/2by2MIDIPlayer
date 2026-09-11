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

## Desktop file launches

Windows and Linux share a single running player per OS user. Before creating the UI
or BASS engine, the launcher acquires `~/.2by2MusicPlayer/instance.lock`. A second
launch sends its absolute file paths to the owner's loopback socket and exits after
acknowledgement. The existing window is restored and the files replace its temporary
playback queue; the saved library and playlists are unchanged. Launching without
arguments brings the existing window forward without changing playback.

Requests received before the window is ready are buffered. Playback waits for
SoundFont preparation when needed. The OS releases the instance lock after an
unexpected exit; the next owner overwrites the stale endpoint information. If the
owner cannot accept a request, the launcher shows an error instead of opening a
second player.

Build the Linux application image on Linux with `./gradlew :desktopApp:createDistributable`.
Run `desktopApp/build/compose/binaries/main/app/2by2MusicPlayer/bin/2by2MusicPlayer`,
optionally followed by quoted MIDI paths. Keep the complete application directory,
including `lib`, together when copying it to another location.

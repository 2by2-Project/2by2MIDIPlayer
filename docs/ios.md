# iOS

The iOS app shows the same shared Compose screens as Android, Windows and Linux. It is built on
macOS only; without a Mac, GitHub Actions builds it (`.github/workflows/ios.yml`).

## Layout

```text
shared/src/commonMain   Screens and logic for every platform
shared/src/mobileMain   Smartphone parts shared by Android and iOS (not desktop)
  PlayerSheet.kt, CompactPlayerSheet.kt   draggable mini player / Now Playing sheet
  playback/PlaybackQueue.kt               queue, wrap-around and shuffle order
  playback/LoopFadePolicy.kt              CC#111: repeat once, then fade into the next song
  ui/playlist/MobilePlaylistDialogs.kt    add to playlist, delete playlist
shared/src/iosMain      iOS actuals (text decoding, strings, platform identity)
iosApp/                 Gradle module :iosApp, the iOS host (framework "PlayerKit")
  src/iosMain           controller, files, AVAudioSession, lock screen controls, pickers, Compose host
  src/bass              BASS/BASSMIDI through cinterop (macOS builds)
  src/bassStub          same API without BASS, for type checks on Windows/Linux
  xcode/                XcodeGen spec, Swift entry point, Info.plist, app icon
```

Android uses the `mobileMain` parts as well. When an Android behavior is needed on iOS, move it to
`mobileMain` instead of writing a second version in `:iosApp`.

## BASS / BASSMIDI

The official iOS XCFrameworks (dynamic frameworks) go to the Git-ignored `proprietary/ios/`:

```text
proprietary/ios/bass.xcframework     bass.h     bass.txt
proprietary/ios/bassmidi.xcframework bassmidi.h bassmidi.txt
```

They come from `bass24-ios.zip` and `bassmidi24-ios.zip` on un4seen.com; CI downloads them. The
Kotlin framework is static, and the app target links and embeds both XCFrameworks.

## Building

On macOS (Xcode, JDK 17+, XcodeGen):

```text
cd iosApp/xcode && xcodegen generate
open iosApp.xcodeproj
```

Xcode's pre-build script runs `./gradlew :iosApp:embedAndSignAppleFrameworkForXcode`.

CI produces an unsigned IPA (`2by2MIDIPlayer-<version>-ios-unsigned.ipa`) as a workflow artifact,
and release tags attach it to the GitHub Release. Install it with a tool that signs it for the device
(AltStore, Sideloadly, or a developer certificate).

On Windows or Linux the iOS Kotlin code can be type-checked (no linking, no BASS cinterop):

```text
./gradlew :shared:compileKotlinIosArm64 :iosApp:compileKotlinIosArm64 -Pkotlin.native.enableKlibsCrossCompilation=true
```

## Files and storage

- Browse starts in the app's Documents folder, shown in the Files app as "On My iPhone › 2by2 MIDI
  Player" (`UIFileSharingEnabled`, `LSSupportsOpeningDocumentsInPlace`). Copy MIDI folders there with
  the Files app or Finder.
- The folder button links a folder elsewhere (iCloud Drive, other apps, USB drives). It is kept as a
  security-scoped bookmark and appears at the start folder and in the address bar's root menu.
- MIDI files opened from other apps, or with the file button, are copied to `Documents/Imported`
  and play as a temporary queue.
- Library keys are stored relative to their root (`doc:/Music/a.mid`, `ext-<id>:/a.mid`,
  `demo:/a.mid`), because iOS may move the app container on updates.
- Settings use Android's DataStore key names in NSUserDefaults. SoundFonts are copied to
  `Application Support/SoundFonts`.

## Playback

BASS runs on one control thread with the same two-synth loop renderer as Android and desktop.
The audio session uses the playback category with background audio. Interruptions pause and resume
when iOS allows it, and disconnecting headphones pauses (Android's audio focus and "becoming noisy").
Lock screen and Control Center show the song and offer play/pause, next, back to start, seeking, and
the loop and shuffle toggles, as Android's media notification does. The piano roll is delayed by the
route's output latency (Bluetooth) plus the piano roll timing setting.

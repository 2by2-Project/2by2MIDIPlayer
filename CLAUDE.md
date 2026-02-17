# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Language Preference
- 常に日本語での応答を心がけてください (Always respond in Japanese)

## Development Workflow
**CRITICAL**: After implementing code changes, ALWAYS build before reporting completion:
1. Implement code changes
2. Set JAVA_HOME: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`
3. Run: `./gradlew assembleDebug`
4. Fix any compilation errors
5. Only report completion when build succeeds
6. User will test on physical device (Pixel 10)

## Project Overview
2by2 MIDI Player is an Android MIDI player app that uses BASS/BASSMIDI for audio playback and ktmidi for MIDI file parsing. The app features loop point detection (CC#111), visual piano roll editing, and SoundFont management.

## Build Commands

**IMPORTANT**: Set JAVA_HOME to Android Studio's JBR before building:
```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

### Build APK
```bash
./gradlew assembleDebug        # Debug APK (faster, skips Lint strict mode)
./gradlew build                # Full build with tests and Lint
./gradlew assembleRelease      # Release APK
```

### Install and Run
```bash
./gradlew installDebug         # Install debug build to connected device
./gradlew installRelease       # Install release build
```

### Clean Build
```bash
./gradlew clean
./gradlew clean build
```

## Architecture Overview

### Core Components

**PlaybackService** (`PlaybackService.kt`)
- MediaSessionService that manages MIDI playback lifecycle
- Uses BASS/BASSMIDI native libraries for audio rendering
- Implements custom loop point detection via CC#111 MIDI events
- Integrates with Media3 session for notification controls via `BassPlayer`
- Key methods:
  - `loadMidi(uri: String)` - Loads MIDI and SoundFont
  - `play()` / `pause()` - Playback control
  - `setTemporaryLoopPoint(loopMs: Long?)` - For editor mode
  - `handlePlaybackBoundary()` - Loop/fade logic (BASS SYNC callback)

**BassPlayer** (`BassPlayer.kt`)
- Custom `SimpleBasePlayer` implementation bridging BASS to Media3
- Provides Media Session integration for system media controls
- Thread-safe metadata updates via Handler

**MainActivity** (`MainActivity.kt`)
- Jetpack Compose UI with file browser
- Scans storage for .mid files
- Mini player at bottom for current playback
- Long-press actions: Play, Share, Details, Edit Loop Point

**EditLoopPointActivity** (`EditLoopPointActivity.kt`)
- Visual piano roll editor for loop points
- Features:
  - Canvas-based piano roll visualization
  - Draggable position markers (yellow=current, green=loop, red=end)
  - Zoom in/out with auto-scroll
  - 8th note snap-to-grid
  - Independent playback preview
  - Saves edited MIDI with new CC#111 events

**SettingsActivity** (`SettingsActivity.kt`)
- SoundFont management (load .sf2 files or download presets)
- Max voices, effects (reverb/chorus), loop mode settings
- Uses DataStore for persistence

**SettingsDataStore** (`SettingsDataStore.kt`)
- Jetpack DataStore-based settings persistence
- Reactive Flows for settings observation

### MIDI Processing

**ktmidi Library**
- Used for MIDI file parsing and manipulation
- Main classes: `Midi1Music`, `Midi1Event`, `Midi1SimpleMessage`, `Midi1CompoundMessage`
- **IMPORTANT**: ktmidi APIs change frequently between versions
  - Always check GitHub (https://github.com/atsushieno/ktmidi) or Maven for current API
  - Type mismatches (Byte vs Int) are common when API changes
  - `Midi1Music` does NOT have a `write()` method - use custom SMF writer if saving files
- Loop point detection: Scan all tracks for CC#111 (0x6F) events

**MIDI Loop Points**
- CC#111 (LoopStart) marks loop beginning
- CC#116 (LoopEnd) optional for loop end
- Standard used by game music (RPG Maker, etc.)

### BASS Audio Library

**Native Libraries** (`app/src/main/jniLibs/`)
- `libbass.so` - Core audio engine
- `libbassmidi.so` - MIDI synthesis via SoundFont

**Java Interface** (`app/src/main/java/com/un4seen/bass/`)
- `BASS.java` - Core BASS functions
- `BASSMIDI.java` - MIDI-specific functions
- **Reference**: Always check official BASS documentation at un4seen.com
- **Java-specific patterns**: See `app/src/main/java/com/un4seen/bass/` for Android JNI usage

**Key BASS Concepts**
- Streams: Created via `BASSMIDI_StreamCreateFile()` or `BASSMIDI_StreamCreateURL()`
- Channels: Return Int handle for stream operations
- SYNC callbacks: `BASS_ChannelSetSync()` for position-based events (e.g., loop detection)
- Must call `BASS_Init()` before any playback
- Must call `BASS_Free()` on cleanup

## Important Patterns

### Service Binding
Activities bind to PlaybackService using ServiceConnection:
```kotlin
private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        playbackService = (service as PlaybackService.LocalBinder).getService()
    }
    override fun onServiceDisconnected(name: ComponentName?) {
        playbackService = null
    }
}
```

### Compose State Management
- Use `mutableStateOf()` for local UI state
- Use `produceState()` for continuous updates (e.g., playback position)
- Use `LaunchedEffect()` for side effects tied to lifecycle
- No ViewModels - direct Service integration

### MIDI File Writing
ktmidi lacks write functionality. Use custom SMF writer:
- Encode MThd chunk (header): format, track count, ticks per quarter note
- Encode MTrk chunks for each track
- Use Variable Length Quantity (VLQ) for delta times
- See `writeMidiToBytes()` in `EditLoopPointActivity.kt`

### Loop Point Editing
- Load MIDI into PlaybackService for audio preview
- Use `setTemporaryLoopPoint()` to override loop behavior during editing
- Reset with `setTemporaryLoopPoint(null)` on Activity destruction
- 8th note quantization: `ticksPerQuarterNote / 2`
- MS ↔ Tick conversion: Use binary search for MS→Tick (no reverse API in ktmidi)

## Common Issues

### ktmidi API Changes
- **Problem**: Unresolved reference errors, type mismatches
- **Solution**: Check GitHub releases and Maven documentation for current API
- **Example**: `music.format` returns Byte, may need `.toInt()` conversion

### BASS Stream Management
- **Problem**: Playback fails silently
- **Solution**: Check `BASS_ErrorGetCode()` after operations
- **Pattern**: Always call `BASS_StreamFree()` when changing tracks

### File Saving Failures
- **Problem**: ContentResolver write fails
- **Solution**: Use `openOutputStream(uri, "wt")` for write mode, create parent directories with `mkdirs()`

### Compose Recomposition Issues
- **Problem**: Draggable handles don't update position
- **Solution**: Use `remember(key)` with position as key to force recomposition
- **Pattern**: Track offset from initial position, reset on drag end

## File Locations

### Key Source Files
- `app/src/main/java/jp/project2by2/musicplayer/` - Main app code
- `app/src/main/java/com/un4seen/bass/` - BASS JNI interface
- `app/src/main/jniLibs/` - Native libraries (BASS, BASSMIDI)
- `app/src/main/res/values/strings.xml` - Localized strings
- `app/src/main/AndroidManifest.xml` - App configuration

### Assets
- `app/src/main/assets/demo/` - Sample MIDI files bundled with app

## Testing Notes

### Manual Testing
- Test on physical device (emulator may have audio issues)
- Test with various MIDI files (with/without CC#111 loop points)
- Test SoundFont switching during playback
- Test loop editing with large files (10MB+)

### Edge Cases
- Files without loop points
- Multiple CC#111 events in one file
- Tempo changes affecting MS/Tick conversion
- Very large MIDI files (memory constraints)

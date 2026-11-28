package jp.project2by2.musicplayer.ios

import jp.project2by2.musicplayer.ui.player.PIANO_ROLL_TIMING_MAX_MS
import platform.Foundation.NSData
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages

data class IosPlaylist(val id: String, val name: String, val tracks: List<String> = emptyList())

/** A folder outside the app container, opened through the document picker and kept as a bookmark. */
data class LinkedFolderRecord(val id: String, val bookmark: NSData)

/** Settings as Android keeps them in DataStore, with the same defaults. */
data class IosSettings(
    val soundFontName: String? = null,
    /** File name of the active bank inside Application Support/SoundFonts. */
    val soundFontFile: String? = null,
    val effectsEnabled: Boolean = false,
    val reverbStrength: Float = 1f,
    val loopEnabled: Boolean = false,
    val shuffleEnabled: Boolean = false,
    /** 0 = grid, 1 = list (Android's folder_view_mode). */
    val folderViewMode: Int = 1,
    val browseFolder: String? = null,
    val maxVoices: Int = 40,
    val pianoRollTimingMs: Int = 0,
)

/**
 * NSUserDefaults with Android's DataStore key names. Library keys are stored relative to their
 * root ("doc:/Music/a.mid"), so the app container moving on update does not break them.
 */
class IosStore(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) {
    fun settings() = IosSettings(
        soundFontName = defaults.stringForKey("sound_font_name"),
        soundFontFile = defaults.stringForKey("sound_font_file"),
        effectsEnabled = defaults.boolForKey("effects_enabled"),
        reverbStrength = float("reverb_strength", 1f).coerceIn(0f, 3f),
        loopEnabled = defaults.boolForKey("loop_enabled"),
        shuffleEnabled = defaults.boolForKey("shuffle_enabled"),
        folderViewMode = int("folder_view_mode", 1).coerceIn(0, 1),
        browseFolder = defaults.stringForKey("browse_folder"),
        maxVoices = int("max_voices", 40).coerceIn(1, 1000),
        pianoRollTimingMs = int("piano_roll_timing_ms", 0).coerceIn(-PIANO_ROLL_TIMING_MAX_MS, PIANO_ROLL_TIMING_MAX_MS),
    )

    fun save(settings: IosSettings) {
        putString("sound_font_name", settings.soundFontName)
        putString("sound_font_file", settings.soundFontFile)
        defaults.setBool(settings.effectsEnabled, "effects_enabled")
        defaults.setFloat(settings.reverbStrength, "reverb_strength")
        defaults.setBool(settings.loopEnabled, "loop_enabled")
        defaults.setBool(settings.shuffleEnabled, "shuffle_enabled")
        defaults.setInteger(settings.folderViewMode.toLong(), "folder_view_mode")
        putString("browse_folder", settings.browseFolder)
        defaults.setInteger(settings.maxVoices.toLong(), "max_voices")
        defaults.setInteger(settings.pianoRollTimingMs.toLong(), "piano_roll_timing_ms")
    }

    fun playlists(): List<IosPlaylist> = defaults.arrayForKey("playlists").orEmpty().mapNotNull { entry ->
        val map = entry as? Map<*, *> ?: return@mapNotNull null
        val id = map["id"] as? String ?: return@mapNotNull null
        IosPlaylist(id, map["name"] as? String ?: "Playlist", (map["tracks"] as? List<*>).orEmpty().filterIsInstance<String>())
    }

    fun savePlaylists(playlists: List<IosPlaylist>) {
        defaults.setObject(playlists.map { mapOf("id" to it.id, "name" to it.name, "tracks" to it.tracks) }, "playlists")
    }

    fun linkedFolders(): List<LinkedFolderRecord> = defaults.arrayForKey("linked_folders").orEmpty().mapNotNull { entry ->
        val map = entry as? Map<*, *> ?: return@mapNotNull null
        LinkedFolderRecord(map["id"] as? String ?: return@mapNotNull null, map["bookmark"] as? NSData ?: return@mapNotNull null)
    }

    fun saveLinkedFolders(folders: List<LinkedFolderRecord>) {
        defaults.setObject(folders.map { mapOf("id" to it.id, "bookmark" to it.bookmark) }, "linked_folders")
    }

    private fun putString(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, key)
    }

    private fun int(key: String, default: Int) =
        if (defaults.objectForKey(key) == null) default else defaults.integerForKey(key).toInt()

    private fun float(key: String, default: Float) =
        if (defaults.objectForKey(key) == null) default else defaults.floatForKey(key).takeIf { it.isFinite() } ?: default
}

/** Japanese when it is the first preferred language, as iOS chooses an app's localization. */
val iosJapanese: Boolean by lazy { (NSLocale.preferredLanguages.firstOrNull() as? String)?.startsWith("ja") == true }

/** Host text that is not shown through playerString (built outside composition). */
fun localized(english: String, japanese: String) = if (iosJapanese) japanese else english

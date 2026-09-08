package jp.project2by2.musicplayer.model

data class LibraryTrack(
    val uri: String, val title: String, val secondary: String?, val durationMs: Long,
    val playlistItemId: Long? = null, val loopPointMs: Long? = null
) {
    fun displayTitle() = title
    fun displaySecondaryText() = secondary
}
data class LibraryFolder(val key: String, val name: String)
data class LibraryPlaylist(val id: String, val name: String, val itemCount: Int)
enum class MidiListContext { Browse, Search, Playlist }
enum class MidiFileAvailability { Unknown, Available, Missing }
enum class FolderViewMode { Grid, List }

package jp.project2by2.musicplayer.ui.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.project2by2.musicplayer.model.LibraryPlaylist
import jp.project2by2.musicplayer.ui.player.playerString

/** Choose the playlist a song is added to, or create one first (Android and iOS). */
@Composable
fun AddToPlaylistDialog(
    title: String,
    playlists: List<LibraryPlaylist>,
    onDismiss: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onAdd: (LibraryPlaylist) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ElevatedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCreatePlaylist
                ) {
                    Text(text = playerString("action_create_playlist"))
                }
                if (playlists.isEmpty()) {
                    Text(text = playerString("info_no_playlists_found"))
                } else {
                    playlists.forEach { playlist ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onAdd(playlist) }
                        ) {
                            Text(text = playlist.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = playerString("cancel"))
            }
        }
    )
}

/** Asks before deleting a playlist; the songs themselves are not deleted. */
@Composable
fun ConfirmDeletePlaylistDialog(
    playlistName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = playerString("action_delete_playlist")) },
        text = {
            Text(text = playerString("confirm_delete_playlist_message").replace("%1\$s", playlistName))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = playerString("action_delete"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = playerString("cancel"))
            }
        }
    )
}

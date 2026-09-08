@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package jp.project2by2.musicplayer.ui.playlist

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.unit.dp
import jp.project2by2.musicplayer.*
import jp.project2by2.musicplayer.model.*
import jp.project2by2.musicplayer.state.*
import jp.project2by2.musicplayer.ui.player.playerString
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    val focusRequesterPlaylistNameField = remember { FocusRequester() }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = playerString("action_create_playlist")) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(playerString("playlist_name_hint")) },
                modifier = Modifier.focusRequester(focusRequesterPlaylistNameField)
            )
            LaunchedEffect(Unit) {
                focusRequesterPlaylistNameField.requestFocus()
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank()
            ) {
                Text(text = playerString("action_create"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = playerString("cancel"))
            }
        }
    )
}

@Composable
fun RenamePlaylistDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    val focusRequesterPlaylistNameField = remember { FocusRequester() }
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = playerString("action_rename_playlist")) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(playerString("playlist_name_hint")) },
                modifier = Modifier.focusRequester(focusRequesterPlaylistNameField)
            )
            LaunchedEffect(Unit) {
                focusRequesterPlaylistNameField.requestFocus()
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name) },
                enabled = name.isNotBlank()
            ) {
                Text(text = playerString("save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = playerString("cancel"))
            }
        }
    )
}

@Composable
fun PlaylistActionsDialog(
    title: String,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ElevatedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.EditNote, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = playerString("action_edit"))
                }
                ElevatedButton(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.DriveFileRenameOutline, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = playerString("action_rename"))
                }
                ElevatedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = playerString("action_delete"))
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

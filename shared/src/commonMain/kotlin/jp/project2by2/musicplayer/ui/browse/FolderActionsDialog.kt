package jp.project2by2.musicplayer.ui.browse

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import jp.project2by2.musicplayer.ui.player.playerString

@Composable
fun FolderActionsDialog(title: String, summary: String, onDismiss: () -> Unit,
    onOpen: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectionContainer {
                Text(summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            }
            ElevatedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("フォルダを開く")
            }
            ElevatedButton(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.DriveFileRenameOutline, null)
                Spacer(Modifier.width(8.dp))
                Text("名前の変更")
            }
            ElevatedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Delete, null)
                Spacer(Modifier.width(8.dp))
                Text("ライブラリから削除")
            }
        }
    }, confirmButton = {}, dismissButton = {
        TextButton(onClick = onDismiss) { Text(playerString("cancel")) }
    })
}

@Composable
fun RenameFolderDialog(initialName: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val focus = remember { FocusRequester() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("フォルダ名の変更") }, text = {
        OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.focusRequester(focus))
    }, confirmButton = {
        TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text(playerString("save")) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(playerString("cancel")) } })
    LaunchedEffect(Unit) { focus.requestFocus() }
}

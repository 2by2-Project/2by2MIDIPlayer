package jp.project2by2.musicplayer.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Desktop sharing destinations; Android hosts keep their system share sheet. */
@Composable
fun FileShareDialog(name: String, error: String?, onCopyFile: () -> Unit, onExportFile: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(name) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(playerString("share_file_hint"))
            ElevatedButton(onClick = onCopyFile, modifier = Modifier.fillMaxWidth()) { Text(playerString("share_copy_file")) }
            ElevatedButton(onClick = onExportFile, modifier = Modifier.fillMaxWidth()) { Text(playerString("share_export_file")) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(playerString("cancel")) } })
}

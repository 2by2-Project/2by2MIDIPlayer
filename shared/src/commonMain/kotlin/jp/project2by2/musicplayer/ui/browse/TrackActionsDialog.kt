@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package jp.project2by2.musicplayer.ui.browse

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

@Composable
fun MidiFileActionsDialog(
    title: String,
    onDismiss: () -> Unit,
    showPlayAction: Boolean = true,
    loopEditEnabled: Boolean = true,
    onPlay: () -> Unit,
    onShare: (() -> Unit)? = null,
    onDetails: (() -> Unit)? = null,
    onEditLoopPoint: (() -> Unit)? = null,
    onAddToPlaylist: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (showPlayAction) {
                    ElevatedButton(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
                        Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = playerString("action_play_in_next_track"))
                    }
                }
                if (onShare != null) ElevatedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = playerString("action_share"))
                }
                if (onDetails != null) ElevatedButton(onClick = onDetails, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.Info, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = playerString("action_details"))
                }
                ElevatedButton(onClick = onAddToPlaylist, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Default.QueueMusic, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = playerString("action_add_to_playlist"))
                }
                if (onEditLoopPoint != null) ElevatedButton(
                    onClick = onEditLoopPoint,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = loopEditEnabled
                ) {
                    Icon(imageVector = Icons.Filled.Edit, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = playerString("action_edit_loop_point"))
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

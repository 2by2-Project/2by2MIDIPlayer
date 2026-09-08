@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package jp.project2by2.musicplayer.ui.player

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
fun PlayerNavigationBar(playlistsSelected: Boolean, onBrowse: () -> Unit, onPlaylists: () -> Unit) {
    NavigationBar {
        NavigationBarItem(selected = !playlistsSelected, onClick = onBrowse,
            icon = { Icon(Icons.Default.Folder, playerString("browse"), Modifier.size(20.dp)) },
            label = { Text(playerString("browse")) })
        NavigationBarItem(selected = playlistsSelected, onClick = onPlaylists,
            icon = { Icon(Icons.Default.QueueMusic, playerString("playlists"), Modifier.size(20.dp)) },
            label = { Text(playerString("playlists")) })
    }
}

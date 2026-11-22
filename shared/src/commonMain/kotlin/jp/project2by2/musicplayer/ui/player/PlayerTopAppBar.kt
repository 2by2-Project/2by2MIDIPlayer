package jp.project2by2.musicplayer.ui.player

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The browser/player toolbar style is defined once for every host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(title = title, modifier = modifier, navigationIcon = navigationIcon, actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f)))
}

/**
 * The library toolbar on every platform: the logo, or the open playlist's name with a back arrow.
 * While a playlist is edited ([editingName] non-null) the name turns into a text field for renaming.
 * Browse shows its location in the address bar below instead, so its toolbar keeps the logo.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryTopAppBar(
    openPlaylistName: String?,
    onClosePlaylist: () -> Unit,
    modifier: Modifier = Modifier,
    editingName: String? = null,
    onEditingNameChange: (String) -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    PlayerTopAppBar(
        modifier = modifier,
        title = {
            if (openPlaylistName == null) PlayerLogo() else AnimatedContent(
                targetState = editingName != null,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) },
                label = "PlaylistTitle"
            ) { editing ->
                if (editing) OutlinedTextField(
                    value = editingName.orEmpty(),
                    onValueChange = onEditingNameChange,
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    placeholder = { Text(openPlaylistName, maxLines = 1) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium
                ) else Text(
                    text = openPlaylistName,
                    modifier = Modifier.fillMaxWidth().clipToBounds().basicMarquee(Int.MAX_VALUE),
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }
        },
        navigationIcon = {
            if (openPlaylistName != null) IconButton(onClick = onClosePlaylist) {
                Icon(Icons.Default.ArrowBack, playerString("back"))
            }
        },
        actions = actions
    )
}

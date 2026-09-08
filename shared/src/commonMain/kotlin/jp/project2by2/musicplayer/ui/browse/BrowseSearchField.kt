package jp.project2by2.musicplayer.ui.browse

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import jp.project2by2.musicplayer.ui.player.playerString

/** Android's expanding search field, immediately below the toolbar on every platform. */
@Composable
fun BrowseSearchField(visible: Boolean, query: String, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    AnimatedVisibility(visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }, animationSpec = tween(200)) + fadeIn(tween(200)),
        exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(200)) + fadeOut(tween(200))) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f)) {
            OutlinedTextField(value = query, onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .zIndex(0f).focusRequester(focusRequester),
                placeholder = { Text(playerString("topbar_search_summary")) }, singleLine = true)
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        }
    }
}

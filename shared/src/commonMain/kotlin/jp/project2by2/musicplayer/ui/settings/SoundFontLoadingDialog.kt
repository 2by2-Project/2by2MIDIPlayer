package jp.project2by2.musicplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import jp.project2by2.musicplayer.ui.player.playerString
import kotlinx.coroutines.delay

/** Shared modal: quick imports never flash a dialog; long imports cannot be dismissed. */
@Composable
fun SoundFontLoadingDialog(loading: Boolean) {
    var visible by remember(loading) { mutableStateOf(false) }
    LaunchedEffect(loading) { if (loading) { delay(300); visible = true } }
    if (loading && visible) AlertDialog(
        onDismissRequest = {}, confirmButton = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(playerString("settings_soundfont_loading_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
            }
        }
    )
}

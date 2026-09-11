package jp.project2by2.musicplayer.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import jp.project2by2.musicplayer.ui.player.playerString
import jp.project2by2.musicplayer.platform.currentPlatform

data class SoundFontOption(val name: String, val url: String, val sizeKey: String)

val recommendedSoundFonts = listOf(
    SoundFontOption("FluidR3 GM-GS", "https://archive.org/download/fluidr3-gm-gs/FluidR3_GM_GS.sf2", "soundfont_size_small"),
    SoundFontOption("General User GS", "https://github.com/mrbumpy409/GeneralUser-GS/raw/refs/heads/main/GeneralUser-GS.sf2", "soundfont_size_medium"),
    SoundFontOption("SGM-V2.01", "https://archive.org/download/SGM-V2.01/SGM-V2.01.sf2", "soundfont_size_large")
)

/** Shared UI decides OS visibility; hosts supply I/O and the optional Windows action. */
@Composable
fun RecommendedSoundFontDialog(
    onDismiss: () -> Unit,
    onDownload: (SoundFontOption) -> Unit,
    isDownloading: Boolean = false,
    downloadProgress: Float? = null,
    onUseWindowsSoundFont: (() -> Unit)? = null,
) {
    var selected by remember { mutableStateOf(recommendedSoundFonts.first()) }
    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !isDownloading, dismissOnClickOutside = !isDownloading),
        title = { Text(playerString("soundfont_dialog_title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (currentPlatform.isWindows && onUseWindowsSoundFont != null) Card(
                    onClick = onUseWindowsSoundFont, enabled = !isDownloading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(playerString("soundfont_windows_title"), style = MaterialTheme.typography.titleMedium)
                        Text(playerString("soundfont_windows_action"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f))
                    }
                }
                Text(playerString("soundfont_dialog_message"), style = MaterialTheme.typography.bodyMedium)
                Column {
                    recommendedSoundFonts.forEach { option ->
                        Row(Modifier.fillMaxWidth().selectable(selected == option, enabled = !isDownloading,
                            role = Role.RadioButton, onClick = { selected = option }).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected == option, onClick = null, enabled = !isDownloading)
                            Spacer(Modifier.width(8.dp))
                            Text(option.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.width(8.dp))
                            Text(playerString(option.sizeKey), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (isDownloading) {
                    Text(playerString("soundfont_downloading"), style = MaterialTheme.typography.bodySmall)
                    if (downloadProgress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { downloadProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(onClick = { onDownload(selected) }, enabled = !isDownloading) {
                Text(playerString("soundfont_download_button"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDownloading) { Text(playerString("soundfont_dialog_dismiss")) }
        }
    )
}

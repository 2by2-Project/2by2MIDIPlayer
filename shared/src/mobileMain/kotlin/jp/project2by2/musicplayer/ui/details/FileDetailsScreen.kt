package jp.project2by2.musicplayer.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.project2by2.musicplayer.ui.player.playerString
import jp.project2by2.musicplayer.ui.settings.SettingsInfoItem

/** What the details screen shows; hosts read it from their file access ([unknown] for missing values). */
data class FileDetails(
    val displayName: String?,
    val parentFolder: String?,
    val format: String?,
    val durationMs: Long,
    /** CC#111 loop start, or null when the file has none. */
    val loopPointMs: Long?,
    val midiTitle: String?,
    val midiArtist: String?,
)

/** Android's FileDetailsActivity screen, shared with iOS. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailsScreen(details: FileDetails?, onBack: () -> Unit) {
    val unknown = playerString("unknown")
    fun value(text: String?) = text?.takeIf { it.isNotBlank() } ?: unknown
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(value(details?.displayName)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = playerString("back"))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            LazyColumn(
                modifier = Modifier.padding(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item { SettingsInfoItem(title = playerString("file_details_parent_folder"), value = value(details?.parentFolder)) }
                item { SettingsInfoItem(title = playerString("file_details_format"), value = value(details?.format)) }
                item { SettingsInfoItem(title = playerString("file_details_duration"), value = formatDetailsDuration(details?.durationMs ?: 0L)) }
                item {
                    SettingsInfoItem(
                        title = playerString("file_details_loop_point"),
                        value = details?.loopPointMs?.let { formatDetailsDuration(it) } ?: playerString("file_details_loop_none")
                    )
                }
                item { HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp)) }
                item { SettingsInfoItem(title = playerString("file_details_midi_title"), value = value(details?.midiTitle)) }
                item { SettingsInfoItem(title = playerString("file_details_midi_artist"), value = value(details?.midiArtist)) }
            }
        }
    }
}

@Composable
private fun formatDetailsDuration(durationMs: Long): String {
    if (durationMs <= 0L) return playerString("duration_placeholder")
    val totalSeconds = durationMs / 1000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

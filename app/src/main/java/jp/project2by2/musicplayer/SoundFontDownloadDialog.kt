package jp.project2by2.musicplayer

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

data class SoundFontOption(
    val nameResId: Int,
    val url: String,
    val size: Int,
)

private val soundFontOptions = listOf(
    SoundFontOption(
        nameResId = R.string.soundfont_fluidr3_gm_gs,
        url = "https://archive.org/download/fluidr3-gm-gs/FluidR3_GM_GS.sf2",
        size = 1,
    ),
    SoundFontOption(
        nameResId = R.string.soundfont_general_user_gs,
        url = "https://github.com/mrbumpy409/GeneralUser-GS/raw/refs/heads/main/GeneralUser-GS.sf2",
        size = 2,
    ),
    SoundFontOption(
        nameResId = R.string.soundfont_sgm_v2_01,
        url = "https://archive.org/download/SGM-V2.01/SGM-V2.01.sf2",
        size = 3,
    )
)

@Composable
fun SoundFontDownloadDialog(
    onDismiss: () -> Unit,
    onDownloadComplete: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedOption by remember { mutableStateOf(soundFontOptions[0]) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = {
            Text(text = stringResource(id = R.string.soundfont_dialog_title))
        },
        text = {
            Column {
                Text(
                    text = stringResource(id = R.string.soundfont_dialog_message),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))

                soundFontOptions.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedOption == option,
                                enabled = !isDownloading,
                                onClick = { selectedOption = option }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedOption == option,
                            enabled = !isDownloading,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(id = option.nameResId),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        var displaySizeText = ""
                        if (option.size == 1) displaySizeText = stringResource(R.string.soundfont_size_small)
                        if (option.size == 2) displaySizeText = stringResource(R.string.soundfont_size_medium)
                        if (option.size == 3) displaySizeText = stringResource(R.string.soundfont_size_large)
                        Text(
                            text = displaySizeText,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (isDownloading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Column {
                        Text(
                            text = stringResource(id = R.string.soundfont_downloading),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        isDownloading = true
                        val success = downloadAndSetSoundFont(
                            context = context,
                            url = selectedOption.url,
                            onProgress = { progress ->
                                downloadProgress = progress
                            }
                        )
                        isDownloading = false
                        if (success) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.soundfont_download_success),
                                Toast.LENGTH_SHORT
                            ).show()
                            onDownloadComplete()
                            onDismiss()
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.soundfont_download_failed),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                enabled = !isDownloading
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp).size(24.dp)
                    )
                }
                Text(text = stringResource(id = R.string.soundfont_download_button))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDownloading
            ) {
                Text(text = stringResource(id = R.string.soundfont_dialog_dismiss))
            }
        }
    )
}

private suspend fun downloadAndSetSoundFont(
    context: Context,
    url: String,
    onProgress: (Float) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    try {
        val connection = URL(url).openConnection()
        connection.connect()

        val fileLength = connection.contentLength
        val cacheSoundFontFile = File(context.cacheDir, "soundfont.sf2")

        connection.getInputStream().use { input ->
            cacheSoundFontFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    if (fileLength > 0) {
                        val progress = totalBytesRead.toFloat() / fileLength.toFloat()
                        withContext(Dispatchers.Main) {
                            onProgress(progress)
                        }
                    }
                }
            }
        }

        // Set the sound font name
        val fileName = url.substringAfterLast('/')
        SettingsDataStore.setSoundFontName(context, fileName)

        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

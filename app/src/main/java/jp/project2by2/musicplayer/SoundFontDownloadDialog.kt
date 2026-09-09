package jp.project2by2.musicplayer

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import jp.project2by2.musicplayer.soundfont.SoundFontDownloader
import jp.project2by2.musicplayer.ui.settings.RecommendedSoundFontDialog
import kotlinx.coroutines.*
import java.io.File

/** Android supplies cache/DataStore operations; dialog presentation lives in commonMain. */
@Composable
fun SoundFontDownloadDialog(onDismiss: () -> Unit, onDownloadComplete: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    RecommendedSoundFontDialog(
        onDismiss = onDismiss, isDownloading = downloading, downloadProgress = progress,
        onDownload = { option ->
            downloading = true
            progress = null
            scope.launch {
                var downloaded: File? = null
                try {
                    withContext(Dispatchers.IO) {
                        val job = currentCoroutineContext()
                        downloaded = SoundFontDownloader.download(option.url, context.cacheDir,
                            checkpoint = { job.ensureActive() }, onProgress = { progress = it })
                    }
                    ensureActive()
                    android.system.Os.rename(downloaded!!.absolutePath, File(context.cacheDir, "soundfont.sf2").absolutePath)
                    SettingsDataStore.setSoundFontName(context, option.url.substringAfterLast('/'))
                    Toast.makeText(context, R.string.soundfont_download_success, Toast.LENGTH_SHORT).show()
                    onDownloadComplete()
                    onDismiss()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    Toast.makeText(context, R.string.soundfont_download_failed, Toast.LENGTH_LONG).show()
                } finally {
                    downloaded?.delete()
                    downloading = false
                }
            }
        }
    )
}

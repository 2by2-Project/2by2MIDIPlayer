package jp.project2by2.musicplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import jp.project2by2.musicplayer.ui.loop.LoopEditorSource
import jp.project2by2.musicplayer.ui.loop.LoopExportResult
import jp.project2by2.musicplayer.ui.loop.LoopPointEditorScreen
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class EditLoopPointActivity : ComponentActivity() {
    private var isServiceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            (service as? PlaybackService.LocalBinder)?.getService()?.pause()
        }
        override fun onServiceDisconnected(name: ComponentName?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isServiceBound = bindService(
            Intent(this, PlaybackService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        val uri = readUriExtra() ?: run {
            finish()
            return
        }

        setContent {
            _2by2MusicPlayerTheme {
                EditLoopPointScreen(
                    fileUri = uri,
                    onBack = { finish() },
                    onSaved = {
                        setResult(RESULT_OK)
                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun readUriExtra(): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_URI)
        }
    }

    companion object {
        const val EXTRA_URI = "extra_uri"
    }
}

private data class SourceFileInfo(val displayName: String)

/** A MIDI copy waiting for the Storage Access Framework destination. */
private class PendingExport(val bytes: ByteArray, val result: CompletableDeferred<Boolean?>)

/**
 * The editor screen is shared with iOS (mobileMain). This host reads the file, previews it with
 * [LoopPreviewPlayer] and exports the edited copy through the Storage Access Framework.
 */
@Composable
private fun EditLoopPointScreen(
    fileUri: Uri,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val previewPlayer = remember(fileUri) { LoopPreviewPlayer(context.applicationContext) }
    val pendingExport = remember(fileUri) { arrayOfNulls<PendingExport>(1) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val pending = pendingExport[0]
        pendingExport[0] = null
        if (pending == null) return@rememberLauncherForActivityResult
        if (uri == null) {
            pending.result.complete(null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val currentName = resolveDisplayName(context, uri)
                    val writeUri = if (!currentName.endsWith(".mid", ignoreCase = true)) {
                        val newName = if ('.' in currentName) {
                            currentName.substringBeforeLast('.') + ".mid"
                        } else {
                            "$currentName.mid"
                        }
                        DocumentsContract.renameDocument(context.contentResolver, uri, newName) ?: uri
                    } else {
                        uri
                    }
                    context.contentResolver.openOutputStream(writeUri)?.use { it.write(pending.bytes) }
                    true
                }.getOrElse {
                    Log.e("EditLoopPoint", "SAF export failed", it)
                    false
                }
            }
            pending.result.complete(success)
        }
    }

    DisposableEffect(previewPlayer) {
        onDispose {
            previewPlayer.release()
        }
    }

    LoopPointEditorScreen(
        key = fileUri,
        load = {
            withContext(Dispatchers.IO) {
                val initialData = loadLoopEditorInitialDataFast(context, fileUri) ?: return@withContext null
                val info = resolveSourceFileInfo(context, fileUri)
                val previewLoaded = previewPlayer.load(
                    uri = fileUri,
                    previewWindow = PreviewWindow(
                        loopStartMs = initialData.loopPointMs,
                        endMs = initialData.pianoRollData.totalDurationMs
                    )
                )
                LoopEditorSource(info.displayName, initialData, previewPlayer.takeIf { previewLoaded })
            }
        },
        export = { exportName, loopTick, endTick ->
            val bytes = withContext(Dispatchers.IO) {
                buildModifiedMidiBytes(context, fileUri, loopTick, endTick)
            }
            if (bytes == null) {
                LoopExportResult.SerializationFailed
            } else {
                val result = CompletableDeferred<Boolean?>()
                pendingExport[0] = PendingExport(bytes, result)
                createDocumentLauncher.launch(exportName)
                when (result.await()) {
                    true -> LoopExportResult.Saved
                    false -> LoopExportResult.Failed
                    null -> LoopExportResult.Cancelled
                }
            }
        },
        onBack = onBack,
        onSaved = onSaved
    )
}

private suspend fun buildModifiedMidiBytes(
    context: Context,
    sourceUri: Uri,
    loopTick: Int,
    endTick: Int
): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val bytes = context.contentResolver.openInputStream(sourceUri)
            ?.use { it.readBytes() } ?: return@withContext null
        // The MIDI rewrite itself is shared with iOS (LoopPointMidi.kt).
        buildLoopPointMidi(bytes, loopTick, endTick)
    } catch (e: Exception) {
        Log.e("EditLoopPointSave", "Failed to build MIDI bytes", e)
        null
    }
}

private fun resolveSourceFileInfo(context: Context, uri: Uri): SourceFileInfo {
    return SourceFileInfo(displayName = resolveDisplayName(context, uri).ifBlank { "Unknown.mid" })
}

private fun resolveDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            return cursor.getString(nameIndex) ?: "Unknown.mid"
        }
    }
    return uri.lastPathSegment ?: "Unknown.mid"
}

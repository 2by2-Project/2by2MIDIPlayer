package jp.project2by2.musicplayer
import jp.project2by2.musicplayer.ui.details.FileDetails

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FileDetailsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = readUriExtra()
        setContent {
            _2by2MusicPlayerTheme {
                FileDetailsScreen(
                    uri = uri,
                    onBack = { finish() }
                )
            }
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

// The screen itself is shared with iOS (mobileMain); this Activity loads the values.
@Composable
private fun FileDetailsScreen(
    uri: Uri?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var details by remember { mutableStateOf<FileDetails?>(null) }

    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        details = withContext(Dispatchers.IO) {
            loadFileDetails(context, uri)
        }
    }

    jp.project2by2.musicplayer.ui.details.FileDetailsScreen(details = details, onBack = onBack)
}

private fun loadFileDetails(context: Context, uri: Uri): FileDetails {
    val parser = MidiParser(context.contentResolver)
    val metadata = parser.getMetadata(uri)
    return FileDetails(
        displayName = resolveDisplayName(context, uri),
        parentFolder = resolveParentFolderName(context, uri),
        format = resolveMimeType(context, uri),
        durationMs = metadata.durationMs ?: 0L,
        loopPointMs = metadata.loopPointMs,
        midiTitle = metadata.title,
        midiArtist = metadata.copyright
    )
}

private fun resolveDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(idx)
                if (!name.isNullOrBlank()) return name
            }
        }
    return uri.lastPathSegment?.substringAfterLast('/') ?: ""
}

private fun resolveParentFolderName(context: Context, uri: Uri): String {
    if (DemoMidiContract.isDemoUri(uri)) {
        return context.getString(R.string.folder_demo_name)
    }
    if (uri.scheme == "file") {
        return File(uri.path ?: return "").parentFile?.name.orEmpty()
    }
    val projection = if (Build.VERSION.SDK_INT >= 29) {
        arrayOf(
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DATA
        )
    } else {
        arrayOf(MediaStore.Files.FileColumns.DATA)
    }
    return context.contentResolver.query(uri, projection, null, null, null)
        ?.use { cursor ->
            val relIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val dataIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            if (cursor.moveToFirst()) {
                if (relIdx >= 0) {
                    val relativePath = cursor.getString(relIdx)
                    val trimmed = relativePath?.trimEnd('/', '\\').orEmpty()
                    if (trimmed.isNotBlank()) {
                        return@use trimmed.substringAfterLast('/', trimmed.substringAfterLast('\\', trimmed))
                    }
                }
                if (dataIdx >= 0) {
                    val dataPath = cursor.getString(dataIdx)
                    if (!dataPath.isNullOrBlank()) {
                        return@use File(dataPath).parentFile?.name.orEmpty()
                    }
                }
            }
            ""
        }.orEmpty()
}

private fun resolveMimeType(context: Context, uri: Uri): String {
    val contentType = context.contentResolver.getType(uri)
    if (!contentType.isNullOrBlank()) return contentType
    val extension = resolveDisplayName(context, uri).substringAfterLast('.', "")
    if (extension.isBlank()) return ""
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()).orEmpty()
}

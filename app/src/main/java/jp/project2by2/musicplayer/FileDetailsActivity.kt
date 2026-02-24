package jp.project2by2.musicplayer

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.atsushieno.ktmidi.Midi1CompoundMessage
import dev.atsushieno.ktmidi.Midi1Music
import dev.atsushieno.ktmidi.read
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

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

private data class FileDetailsSnapshot(
    val displayName: String,
    val parentFolder: String,
    val mimeType: String,
    val durationMs: Long,
    val loopPoint: LoopPoint?,
    val midiTitle: String?,
    val midiArtist: String?
)

private data class EmbeddedMidiMetadata(
    val title: String?,
    val artist: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileDetailsScreen(
    uri: Uri?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var snapshot by remember {
        mutableStateOf(
            FileDetailsSnapshot(
                displayName = context.getString(R.string.unknown),
                parentFolder = context.getString(R.string.unknown),
                mimeType = context.getString(R.string.unknown),
                durationMs = 0L,
                loopPoint = null,
                midiTitle = null,
                midiArtist = null
            )
        )
    }

    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            loadFileDetails(context, uri)
        }
        snapshot = loaded
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(snapshot.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(id = R.string.back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            LazyColumn(
                modifier = Modifier.padding(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item {
                    SettingsInfoItem(
                        title = stringResource(id = R.string.file_details_parent_folder),
                        value = snapshot.parentFolder
                    )
                }
                item {
                    SettingsInfoItem(
                        title = stringResource(id = R.string.file_details_format),
                        value = snapshot.mimeType
                    )
                }
                item {
                    SettingsInfoItem(
                        title = stringResource(id = R.string.file_details_duration),
                        value = formatDuration(snapshot.durationMs)
                    )
                }
                item {
                    val loopValue = if (snapshot.loopPoint?.hasLoopStartMarker == true) {
                        formatDuration(snapshot.loopPoint!!.startMs)
                    } else {
                        stringResource(id = R.string.file_details_loop_none)
                    }
                    SettingsInfoItem(
                        title = stringResource(id = R.string.file_details_loop_point),
                        value = loopValue
                    )
                }
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                }
                item {
                    SettingsInfoItem(
                        title = stringResource(id = R.string.file_details_midi_title),
                        value = snapshot.midiTitle?.takeIf { it.isNotBlank() }
                            ?: stringResource(id = R.string.unknown)
                    )
                }
                item {
                    SettingsInfoItem(
                        title = stringResource(id = R.string.file_details_midi_artist),
                        value = snapshot.midiArtist?.takeIf { it.isNotBlank() }
                            ?: stringResource(id = R.string.unknown)
                    )
                }
            }
        }
    }
}

@Composable
private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return stringResource(id = R.string.duration_placeholder)
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

private fun loadFileDetails(context: Context, uri: Uri): FileDetailsSnapshot {
    val displayName = resolveDisplayName(context, uri)
    val parentFolder = resolveParentFolderName(context, uri).ifBlank { context.getString(R.string.unknown) }
    val mimeType = resolveMimeType(context, uri).ifBlank { context.getString(R.string.unknown) }

    val (file, isTemp) = resolveFile(context, uri)
    val durationMs = file?.let { calculateMidiDurationMs(it) } ?: 0L
    val loopPoint = file?.let { PlaybackService.findLoopPoint(it) }
    val metadata = file?.let { extractEmbeddedMidiMetadata(it) } ?: EmbeddedMidiMetadata(title = null, artist = null)
    if (isTemp) {
        file?.delete()
    }

    return FileDetailsSnapshot(
        displayName = displayName.ifBlank { context.getString(R.string.unknown) },
        parentFolder = parentFolder,
        mimeType = mimeType,
        durationMs = durationMs,
        loopPoint = loopPoint,
        midiTitle = metadata.title,
        midiArtist = metadata.artist
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

private fun resolveFile(context: Context, uri: Uri): Pair<File?, Boolean> {
    if (uri.scheme == "file") {
        val path = uri.path
        return if (path.isNullOrBlank()) {
            Pair(null, false)
        } else {
            Pair(File(path), false)
        }
    }
    val tempFile = File.createTempFile("details_", ".mid", context.cacheDir)
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        if (inputStream == null) {
            tempFile.delete()
            return Pair(null, true)
        }
        inputStream.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        Pair(tempFile, true)
    } catch (_: Exception) {
        tempFile.delete()
        Pair(null, true)
    }
}

private fun calculateMidiDurationMs(midiFile: File): Long {
    return try {
        midiFile.inputStream().use { inputStream ->
            val bytes = inputStream.readBytes().toList()
            val music = Midi1Music().apply { read(bytes) }

            var maxTick = 0
            for (track in music.tracks) {
                var tick = 0
                for (e in track.events) {
                    tick += e.deltaTime
                }
                if (tick > maxTick) {
                    maxTick = tick
                }
            }

            music.getTimePositionInMillisecondsForTick(maxTick).toLong()
        }
    } catch (_: Exception) {
        0L
    }
}

private fun extractEmbeddedMidiMetadata(midiFile: File): EmbeddedMidiMetadata {
    return runCatching {
        val music = Midi1Music().apply { read(midiFile.readBytes().toList()) }
        data class Candidate(val trackIndex: Int, val tick: Int, val text: String)
        val titleCandidates = mutableListOf<Candidate>()
        val textCandidates = mutableListOf<Candidate>()
        val artistCandidates = mutableListOf<Candidate>()

        for ((trackIndex, track) in music.tracks.withIndex()) {
            var tick = 0
            for (event in track.events) {
                tick += event.deltaTime
                val msg = event.message
                if ((msg.statusByte.toInt() and 0xFF) != 0xFF) continue
                val metaType = msg.msb.toInt() and 0xFF
                val data = (msg as? Midi1CompoundMessage)?.extraData ?: continue
                val text = decodeMidiMetaText(data) ?: continue
                val candidate = Candidate(trackIndex = trackIndex, tick = tick, text = text)

                when (metaType) {
                    0x03 -> titleCandidates.add(candidate)
                    0x01 -> {
                        textCandidates.add(candidate)
                        if (looksLikeArtistField(text)) artistCandidates.add(candidate)
                    }
                    0x02 -> artistCandidates.add(candidate)
                }
            }
        }

        val title = titleCandidates
            .sortedWith(compareBy<Candidate> { it.trackIndex != 0 }.thenBy { it.tick })
            .firstOrNull()
            ?.text
            ?: textCandidates.sortedBy { it.tick }.firstOrNull()?.text
        val artist = artistCandidates.sortedBy { it.tick }.firstOrNull()?.text

        EmbeddedMidiMetadata(title = title, artist = artist)
    }.getOrElse {
        EmbeddedMidiMetadata(title = null, artist = null)
    }
}

private fun decodeMidiMetaText(data: ByteArray): String? {
    if (data.isEmpty()) return null
    val candidates = listOf(
        decodeWithCharsetOrNull(data, Charsets.UTF_8),
        decodeWithCharsetOrNull(data, Charset.forName("MS932")),
        decodeWithCharsetOrNull(data, Charset.forName("EUC-JP")),
        decodeWithCharsetOrNull(data, Charset.forName("ISO-2022-JP")),
        decodeWithCharsetOrNull(data, Charsets.ISO_8859_1)
    ).filterNotNull().map { it.trim().replace('\u0000', ' ') }.filter { it.isNotBlank() }

    if (candidates.isEmpty()) return null
    return candidates.maxByOrNull { scoreDecodedText(it) }
}

private fun decodeMidiMetaText(data: List<Byte>): String? = decodeMidiMetaText(data.toByteArray())

private fun decodeWithCharsetOrNull(data: ByteArray, charset: Charset): String? {
    return try {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(data)).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

private fun scoreDecodedText(text: String): Int {
    var score = 0
    var jpCount = 0
    var replacementCount = 0
    var controlCount = 0
    for (ch in text) {
        when {
            ch == '\uFFFD' -> replacementCount++
            ch.isISOControl() && ch != '\n' && ch != '\r' && ch != '\t' -> controlCount++
            ch in '\u3040'..'\u30FF' || ch in '\u4E00'..'\u9FFF' -> jpCount++
        }
    }
    score += jpCount * 3
    score -= replacementCount * 10
    score -= controlCount * 5
    if (text.any { it.isLetterOrDigit() }) score += 5
    return score
}

private fun looksLikeArtistField(text: String): Boolean {
    val s = text.lowercase()
    return s.contains("artist") ||
        s.contains("composer") ||
        s.contains("arranger") ||
        s.contains("author") ||
        s.contains("music by") ||
        s.contains("written by") ||
        s.startsWith("by ")
}

package jp.project2by2.musicplayer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException

class DemoMidiContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val fileName = DemoMidiContract.fileNameFromUri(uri)
            ?: throw FileNotFoundException("Unknown Demo MIDI URI: $uri")
        val columns = projection ?: DEFAULT_PROJECTION
        val row = Array<Any?>(columns.size) { index ->
            when (columns[index]) {
                OpenableColumns.DISPLAY_NAME -> fileName
                OpenableColumns.SIZE -> null
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply {
            addRow(row)
            setNotificationUri(context?.contentResolver, uri)
        }
    }

    override fun getType(uri: Uri): String {
        ensureDemoUri(uri)
        return MIDI_MIME_TYPE
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        ensureReadOnly(mode)
        val context = context ?: throw FileNotFoundException("Context is unavailable")
        val assetPath = DemoMidiContract.assetPathFromUri(uri)
            ?: throw FileNotFoundException("Unknown Demo MIDI URI: $uri")
        return context.assets.openFd(assetPath)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun ensureDemoUri(uri: Uri) {
        if (URI_MATCHER.match(uri) != DEMO_MIDI) {
            throw IllegalArgumentException("Unsupported URI: $uri")
        }
    }

    private fun ensureReadOnly(mode: String) {
        if (mode != "r") {
            throw FileNotFoundException("Demo MIDI assets are read-only")
        }
    }

    private companion object {
        private const val DEMO_MIDI = 1
        private const val MIDI_MIME_TYPE = "audio/midi"
        private val DEFAULT_PROJECTION = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        private val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(DemoMidiContract.authority, "demo-midi/demo/*", DEMO_MIDI)
        }
    }
}

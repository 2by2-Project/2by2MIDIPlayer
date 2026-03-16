package jp.project2by2.musicplayer

import android.net.Uri

object DemoMidiContract {
    private const val AUTHORITY_SUFFIX = ".demo-midi"
    const val DEMO_FOLDER = "demo"
    private const val CONTENT_PATH = "demo-midi"

    val authority: String
        get() = BuildConfig.APPLICATION_ID + AUTHORITY_SUFFIX

    fun buildUri(fileName: String): Uri {
        return Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath(CONTENT_PATH)
            .appendPath(DEMO_FOLDER)
            .appendPath(fileName)
            .build()
    }

    fun isDemoUri(uri: Uri): Boolean {
        return uri.scheme == "content" &&
            uri.authority == authority &&
            uri.pathSegments.size == 3 &&
            uri.pathSegments[0] == CONTENT_PATH &&
            uri.pathSegments[1] == DEMO_FOLDER
    }

    fun fileNameFromUri(uri: Uri): String? {
        if (!isDemoUri(uri)) return null
        return uri.pathSegments.getOrNull(2)
    }

    fun assetPathFromUri(uri: Uri): String? {
        val fileName = fileNameFromUri(uri) ?: return null
        return "$DEMO_FOLDER/$fileName"
    }
}

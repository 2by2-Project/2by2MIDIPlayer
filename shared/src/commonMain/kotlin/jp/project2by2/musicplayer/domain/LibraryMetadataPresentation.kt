package jp.project2by2.musicplayer

fun midiDisplayTitle(fileName: String, metadataTitle: String?): String = metadataTitle?.takeIf { it.isNotBlank() } ?: fileName

fun midiDisplaySecondaryText(fileName: String, folderName: String, metadataTitle: String?, artist: String?): String? {
    if (metadataTitle.isNullOrBlank()) return folderName.takeIf { it.isNotBlank() }
    return artist?.takeIf { it.isNotBlank() }?.let { "$fileName - $it" } ?: fileName
}

/** Filename, title, copyright/artist and folder follow Android's normalized multi-token search. */
fun matchesMidiSearch(fileName: String, title: String?, artist: String?, folderName: String, query: String): Boolean {
    val tokens = normalizeMidiSearch(query).split(' ').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return true
    val texts = listOfNotNull(fileName.substringBeforeLast('.', fileName), fileName, title, artist, folderName).map(::normalizeMidiSearch)
    return tokens.all { token -> texts.any { it.contains(token) } }
}

internal expect fun normalizeMidiSearch(text: String): String

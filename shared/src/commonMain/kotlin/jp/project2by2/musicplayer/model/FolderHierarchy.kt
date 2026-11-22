package jp.project2by2.musicplayer.model

/**
 * Keys are paths relative to the primary storage root ("Music/Game"). Other volumes keep their
 * absolute key ("/storage/1234-ABCD/Music"); the volume root ("/storage/1234-ABCD") is a single
 * top-level folder rather than a "/storage" chain. Only directories holding library tracks and
 * their ancestors are indexed.
 */
fun libraryFolderHierarchy(keys: List<String>): List<LibraryFolder> = keys
    .filter { it != "assets_demo" }
    .flatMap { key ->
        val parts = key.trim('/').split('/').filter { it.isNotEmpty() }
        val volumeDepth = if (key.startsWith('/')) minOf(2, parts.size) else 1
        (volumeDepth..parts.size).filter { it > 0 }.map { depth ->
            LibraryFolder((if (key.startsWith('/')) "/" else "") + parts.take(depth).joinToString("/"), parts[depth - 1])
        }
    }.distinctBy { it.key }.sortedBy { it.name.lowercase() }

fun parentLibraryFolder(key: String): String? {
    if (key.startsWith('/') && key.trim('/').count { it == '/' } < 2) return null
    return key.substringBeforeLast('/', "").ifEmpty { null }
}

fun childLibraryFolders(folders: List<LibraryFolder>, parent: String?): List<LibraryFolder> =
    folders.filter { parentLibraryFolder(it.key) == parent }

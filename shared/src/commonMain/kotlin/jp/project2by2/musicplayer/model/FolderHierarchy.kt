package jp.project2by2.musicplayer.model

/** Paths are relative to the storage root; only directories containing library tracks are indexed. */
fun libraryFolderHierarchy(keys: List<String>): List<LibraryFolder> = keys
    .filter { it != "assets_demo" }
    .flatMap { key ->
        val parts = key.trim('/').split('/').filter { it.isNotEmpty() }
        parts.indices.map { index ->
            LibraryFolder((if (key.startsWith('/')) "/" else "") + parts.take(index + 1).joinToString("/"), parts[index])
        }
    }.distinctBy { it.key }.sortedBy { it.name.lowercase() }

fun parentLibraryFolder(key: String): String? = key.substringBeforeLast('/', "").ifEmpty { null }

fun childLibraryFolders(folders: List<LibraryFolder>, parent: String?): List<LibraryFolder> =
    folders.filter { parentLibraryFolder(it.key) == parent }

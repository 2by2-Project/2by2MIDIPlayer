package jp.project2by2.musicplayer.model

/*
 * Explorer paths shared by Android and desktop: absolute, '/'-separated paths such as
 * "/storage/emulated/0/Download" or "/home/user/Music". (Windows paths are resolved by the desktop
 * host with java.io.File and only use the presentation below.)
 */

/** Levels of [path], outermost first: "/a/b" -> ["/", "/a", "/a/b"]. */
fun absolutePathLevels(path: String): List<String> {
    val parts = path.split('/').filter { it.isNotEmpty() }
    return listOf("/") + parts.indices.map { depth -> "/" + parts.take(depth + 1).joinToString("/") }
}

fun absolutePathParent(path: String): String? = absolutePathLevels(path).dropLast(1).lastOrNull()

/** The folder's own name; "/" for the root. */
fun absolutePathName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

/** The innermost of [roots] (such as storage volumes) holding [path], or null when none does. */
fun containingRoot(path: String, roots: Collection<String>): String? =
    roots.filter { isWithinPath(path, it) }.maxByOrNull { it.length }

/** Levels of [path] from its [containingRoot] down; every level from "/" when no root holds it. */
fun pathLevelsWithin(path: String, roots: Collection<String>): List<String> {
    val root = containingRoot(path, roots) ?: return absolutePathLevels(path)
    return absolutePathLevels(path).filter { isWithinPath(it, root) }
}

/** Whether [path] is [dir] itself or lies anywhere below it. */
fun isWithinPath(path: String, dir: String): Boolean = path == dir || path.startsWith(childPathPrefix(dir))

/**
 * Folders shown inside [dir]: [listed] subfolders read from disk, plus the next level towards each of
 * [reachable] (folders holding music, storage volumes). The latter keeps folders visible whose parents
 * cannot be listed, such as "/storage" on Android, and folders a platform does not let the app list.
 */
fun explorerChildFolders(dir: String, listed: Collection<String>, reachable: Collection<String>): List<LibraryFolder> {
    val prefix = childPathPrefix(dir)
    val towardsReachable = reachable.asSequence()
        .filter { it.startsWith(prefix) && it.length > prefix.length }
        .map { prefix + it.substring(prefix.length).substringBefore('/') }
    return (listed.asSequence().filter { it.startsWith(prefix) && it.length > prefix.length } + towardsReachable)
        .distinct()
        .map { LibraryFolder(it, absolutePathName(it)) }
        .sortedBy { it.name.lowercase() }
        .toList()
}

private fun childPathPrefix(dir: String) = if (dir.endsWith('/')) dir else "$dir/"

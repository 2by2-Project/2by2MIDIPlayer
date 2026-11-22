package jp.project2by2.musicplayer.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties

/**
 * Desktop folder browsing. Folders are listed from disk like a file explorer (hidden ones left out);
 * the home directory is additionally scanned for MIDI files so a folder can be searched with its
 * subfolders. Paths are canonical absolute paths; [DRIVES] is the virtual folder listing Windows drives.
 */
class DesktopFolderIndex(
    // browse.home.dir lets tests browse an isolated folder instead of the developer's home.
    val home: File = File(System.getProperty("browse.home.dir") ?: System.getProperty("user.home")).canonicalFile,
    private val cacheFile: File? = null,
    val windows: Boolean = jp.project2by2.musicplayer.platform.currentPlatform.isWindows,
    private val roots: () -> List<File> = { File.listRoots().filter { it.exists() } },
) {
    data class Snapshot(
        val files: Set<String> = emptySet(),
        val scanning: Boolean = false,
        /** Bumped when the app changes folders on disk, so open listings are read again. */
        val revision: Int = 0,
    )

    private val mutable = MutableStateFlow(Snapshot())
    val state = mutable.asStateFlow()
    @Volatile var cancelled = false

    fun parent(dir: String): String? = when {
        dir == DRIVES -> null
        else -> File(dir).parentFile?.path ?: if (windows) DRIVES else null
    }

    /** [dir] and its ancestors, outermost first: the levels of the address bar. */
    fun ancestry(dir: String): List<String> = generateSequence(dir, ::parent).toList().asReversed()

    /** Folders shown inside [dir]: drives for [DRIVES], otherwise every visible subfolder on disk. */
    fun children(dir: String): List<File> {
        if (dir == DRIVES) return roots()
        return File(dir).listFiles { file -> file.isDirectory && !file.isHidden }.orEmpty()
            .sortedBy { it.name.lowercase() }
    }

    /** Indexed MIDI files at any depth below [dir], for searching a folder and its subfolders. */
    fun filesUnder(dir: String, snapshot: Snapshot = state.value): List<String> =
        if (dir == DRIVES) snapshot.files.toList()
        else childPrefix(dir).let { prefix -> snapshot.files.filter { it.startsWith(prefix, ignoreCase = windows) } }

    /** MIDI files directly inside [dir], read from disk so the listing is always current. */
    fun tracks(dir: String): List<String> = if (dir == DRIVES) emptyList() else
        File(dir).listFiles { file -> file.isFile && file.isMidi() }.orEmpty()
            .map { it.canonicalPath }.sortedBy { File(it).name.lowercase() }

    /** Resolves a remembered folder: falls back to the nearest existing ancestor, then home. */
    fun resolve(remembered: String?): String {
        if (remembered == DRIVES && windows) return DRIVES
        var dir = remembered?.let(::File)
        while (dir != null && !dir.isDirectory) dir = dir.parentFile
        return dir?.canonicalPath ?: home.path
    }

    fun loadCache() {
        val file = cacheFile?.takeIf { it.isFile } ?: return
        val p = runCatching { Properties().apply { file.inputStream().use(::load) } }.getOrNull() ?: return
        fun count(key: String) = (p.getProperty("$key.count")?.toIntOrNull() ?: 0).coerceIn(0, MAX_FILES)
        val files = (0 until count("files")).mapNotNull { p.getProperty("files.$it") }.toSet()
        mutable.update { it.copy(files = it.files + files) }
    }

    fun saveCache() {
        val file = cacheFile ?: return
        val snapshot = state.value
        val p = Properties()
        p.setProperty("files.count", snapshot.files.size.toString())
        snapshot.files.forEachIndexed { i, path -> p.setProperty("files.$i", path) }
        runCatching {
            file.parentFile.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.outputStream().use { p.store(it, "2by2 MIDI Player folder index") }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Files known from elsewhere (e.g. the former desktop library) keep their folders visible. */
    fun addKnown(paths: Collection<String>) {
        val existing = paths.filter { File(it).isFile && File(it).isMidi() }
        if (existing.isNotEmpty()) mutable.update { it.copy(files = it.files + existing) }
    }

    /** Full scan of the home directory; replaces what was indexed beneath it. */
    fun scanHome() {
        mutable.update { it.copy(scanning = true) }
        try {
            val found = LinkedHashSet<String>()
            walk(home.toPath(), maxDepth = 16, maxDirectories = 200_000) { file ->
                found += file.toString()
                // Publish progress so folders appear while a first scan is still running.
                if (found.size % 100 == 0) found.toSet().let { batch -> mutable.update { it.copy(files = it.files + batch) } }
                found.size < MAX_FILES
            }
            if (cancelled) return
            val prefix = childPrefix(home.path)
            mutable.update { snapshot -> snapshot.copy(
                files = snapshot.files.filterNot { it.startsWith(prefix, ignoreCase = windows) }.toSet() + found,
            ) }
            saveCache()
        } finally {
            mutable.update { it.copy(scanning = false) }
        }
    }

    /** Keeps indexed paths valid after a folder is renamed on disk. */
    fun remap(source: String, target: String) {
        fun move(path: String) = if (path == source || path.startsWith(childPrefix(source))) target + path.substring(source.length) else path
        mutable.update { snapshot -> snapshot.copy(files = snapshot.files.map(::move).toSet(), revision = snapshot.revision + 1) }
        saveCache()
    }

    private fun walk(start: Path, maxDepth: Int, maxDirectories: Int, onMidi: (Path) -> Boolean) {
        if (!Files.isDirectory(start)) return
        var directories = 0
        Files.walkFileTree(start, emptySet(), maxDepth, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = when {
                cancelled || ++directories > maxDirectories -> FileVisitResult.TERMINATE
                dir != start && isExcluded(dir) -> FileVisitResult.SKIP_SUBTREE
                else -> FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (attrs.isRegularFile && file.toFile().isMidi() && !onMidi(file.toAbsolutePath().normalize())) FileVisitResult.TERMINATE
                else FileVisitResult.CONTINUE
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
    }

    /** Hidden folders and system or tool directories never hold a user's music. */
    private fun isExcluded(dir: Path): Boolean {
        val name = dir.fileName?.toString() ?: return false
        if (name.startsWith('.') || name.lowercase() in EXCLUDED_ANYWHERE) return true
        if (windows && runCatching { Files.isHidden(dir) }.getOrDefault(false)) return true
        val atRoot = dir.parent?.parent == null
        return atRoot && name.lowercase() in (if (windows) EXCLUDED_WINDOWS_ROOT else EXCLUDED_UNIX_ROOT)
    }

    private fun childPrefix(dir: String) = if (dir.endsWith(File.separatorChar)) dir else dir + File.separatorChar

    companion object {
        const val DRIVES = "drives:"
        private const val MAX_FILES = 50_000
        private val EXCLUDED_ANYWHERE = setOf("node_modules", "appdata", "\$recycle.bin", "system volume information", "__pycache__")
        private val EXCLUDED_WINDOWS_ROOT = setOf("windows", "program files", "program files (x86)", "programdata", "recovery", "perflogs")
        private val EXCLUDED_UNIX_ROOT = setOf("proc", "sys", "dev", "run", "boot", "snap", "lost+found", "usr", "lib", "lib64", "bin", "sbin", "etc", "var")
    }
}

internal fun File.isMidi() = extension.lowercase() in listOf("mid", "midi")

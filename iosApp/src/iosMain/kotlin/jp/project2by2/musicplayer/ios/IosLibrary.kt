@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package jp.project2by2.musicplayer.ios

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import jp.project2by2.musicplayer.albumArtworkFileNames
import kotlinx.cinterop.*
import org.jetbrains.skia.Image
import platform.Foundation.*
import platform.UIKit.UIDevice
import platform.posix.memcpy

/** A place Browse can start from: the app's Documents, the bundled samples, or a linked folder. */
data class LibraryRoot(val id: String, val path: String, val label: String)

/**
 * Library keys are "<root>:<relative path>": "doc:/Music/a.mid" (Documents, shown in the Files app),
 * "demo:/95020000.MID" (bundled samples) and "ext-<id>:/Sub/b.mid" (a folder added with the picker).
 * Keys never contain the container path, which iOS may change when the app is updated.
 */
class IosLibrary(private val store: IosStore) {
    private val files = NSFileManager.defaultManager
    val documents: String = normalizePath(NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String)
    val support: String = (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String).trimEnd('/')
    private val demoDirectory: String = "${NSBundle.mainBundle.resourcePath}/demo"
    private var linked: List<LibraryRoot> = emptyList()
    private val linkedRecords = mutableMapOf<String, LinkedFolderRecord>()

    val home: String get() = "$DOCUMENTS:"
    // UIKit: read once on the main thread (the library is created there).
    private val documentsLabel = documentsLabel()

    init {
        files.createDirectoryAtPath(support, withIntermediateDirectories = true, attributes = null, error = null)
        reloadLinkedFolders()
    }

    /** Resolves saved bookmarks and keeps their security scope open while the app runs. */
    fun reloadLinkedFolders() {
        val records = store.linkedFolders()
        linkedRecords.clear()
        linked = records.mapNotNull { record ->
            linkedRecords[record.id] = record
            val url = NSURL.URLByResolvingBookmarkData(record.bookmark, options = 0u, relativeToURL = null,
                bookmarkDataIsStale = null, error = null) ?: return@mapNotNull null
            url.startAccessingSecurityScopedResource()
            val path = url.path?.let(::normalizePath) ?: return@mapNotNull null
            LibraryRoot(record.id, path, url.lastPathComponent ?: path)
        }
    }

    fun roots(): List<LibraryRoot> = listOf(LibraryRoot(DOCUMENTS, documents, documentsLabel)) + linked
    fun linkedRoots(): List<LibraryRoot> = linked
    fun isLinkedRoot(key: String) = rootId(key).startsWith(LINKED_PREFIX) && relative(key).isEmpty()

    fun addLinkedFolder(url: NSURL): String {
        url.startAccessingSecurityScopedResource()
        val bookmark = url.bookmarkDataWithOptions(0u, includingResourceValuesForKeys = null, relativeToURL = null, error = null)
            ?: error(localized("Cannot keep access to this folder", "このフォルダへのアクセスを保持できません"))
        val path = url.path?.let(::normalizePath) ?: error("Invalid folder")
        // Adding the same folder again opens the existing entry.
        linked.firstOrNull { it.path == path }?.let { return "${it.id}:" }
        if (path == documents || path.startsWith("$documents/")) return keyForPath(path) ?: home
        val id = LINKED_PREFIX + NSUUID().UUIDString
        store.saveLinkedFolders(store.linkedFolders() + LinkedFolderRecord(id, bookmark))
        reloadLinkedFolders()
        return "$id:"
    }

    fun removeLinkedFolder(key: String) {
        val id = rootId(key)
        store.saveLinkedFolders(store.linkedFolders().filterNot { it.id == id })
        reloadLinkedFolders()
    }

    fun rootId(key: String) = key.substringBefore(':')
    fun relative(key: String) = key.substringAfter(':', "")
    private fun rootPath(id: String): String? = when (id) {
        DOCUMENTS -> documents
        DEMO -> demoDirectory
        else -> linked.firstOrNull { it.id == id }?.path
    }

    /** Absolute path for a key, or null when its linked folder is no longer available. */
    fun resolve(key: String): String? {
        val root = rootPath(rootId(key)) ?: return null
        val rel = relative(key)
        require(rel.split('/').none { it == ".." }) { "Invalid path" }
        return root + rel
    }

    /** Key for a file inside Documents or a linked folder (files opened from other apps). */
    fun keyForPath(path: String): String? {
        val clean = normalizePath(path)
        for (root in roots()) {
            if (clean == root.path) return "${root.id}:"
            if (clean.startsWith(root.path + "/")) return "${root.id}:${clean.removePrefix(root.path)}"
        }
        return null
    }

    fun isDemo(key: String) = rootId(key) == DEMO
    fun fileName(key: String) = relative(key).substringAfterLast('/')

    fun parent(key: String): String? {
        val rel = relative(key)
        if (rel.isEmpty()) return if (rootId(key) == DOCUMENTS) null else home
        return "${rootId(key)}:${rel.substringBeforeLast('/', "")}"
    }

    /** Root first, then each folder down to [key]. */
    fun ancestry(key: String): List<String> {
        val id = rootId(key)
        val parts = relative(key).split('/').filter { it.isNotEmpty() }
        return listOf("$id:") + parts.indices.map { i -> "$id:/" + parts.take(i + 1).joinToString("/") }
    }

    fun label(key: String): String {
        val rel = relative(key)
        if (rel.isNotEmpty()) return rel.substringAfterLast('/')
        return roots().firstOrNull { it.id == rootId(key) }?.label ?: rootId(key)
    }

    fun exists(key: String): Boolean = resolve(key)?.let { files.fileExistsAtPath(it) } == true

    /** Playable now or after an iCloud download (its placeholder is ".name.icloud"). */
    fun isAvailable(key: String): Boolean {
        val path = resolve(key) ?: return false
        if (files.fileExistsAtPath(path)) return true
        return files.fileExistsAtPath(path.substringBeforeLast('/') + "/." + path.substringAfterLast('/') + ".icloud")
    }
    fun isDirectory(key: String): Boolean = resolve(key)?.let(::directory) == true

    private fun directory(path: String): Boolean = memScoped {
        val flag = alloc<BooleanVar>()
        files.fileExistsAtPath(path, flag.ptr) && flag.value
    }

    data class Listing(val dir: String, val folders: List<String>, val tracks: List<String>)

    /** Visible subfolders and MIDI files of [dir], sorted like the Files app. */
    fun list(dir: String): Listing {
        val path = resolve(dir) ?: return Listing(dir, emptyList(), emptyList())
        val names = files.contentsOfDirectoryAtPath(path, error = null).orEmpty().filterIsInstance<String>()
        val folders = mutableListOf<String>()
        val tracks = mutableListOf<String>()
        val base = dir.trimEnd('/')
        for (name in names) {
            // iCloud keeps files that are not downloaded yet as hidden ".name.icloud" placeholders.
            val placeholder = name.startsWith('.') && name.endsWith(".icloud")
            val visible = if (placeholder) name.removePrefix(".").removeSuffix(".icloud") else name
            if (visible.startsWith('.')) continue
            val key = "$base/$visible"
            if (!placeholder && directory("$path/$name")) folders += key
            else if (isMidiName(visible)) tracks += key
        }
        val order = compareBy<String, String>(NaturalOrder) { fileName(it) }
        return Listing(dir, folders.sortedWith(order), tracks.sortedWith(order))
    }

    /** Every MIDI file below [dir], depth-first, reported in batches until [stop] returns true. */
    fun walk(dir: String, stop: () -> Boolean, onFound: (List<String>) -> Unit) {
        val found = mutableListOf<String>()
        fun visit(key: String, depth: Int) {
            if (stop() || depth > MAX_DEPTH || found.size >= MAX_FILES) return
            val listing = list(key)
            found += listing.tracks
            onFound(found.toList())
            listing.folders.forEach { visit(it, depth + 1) }
        }
        visit(dir, 0)
        onFound(found.toList())
    }

    fun demos(): List<String> = files.contentsOfDirectoryAtPath(demoDirectory, error = null).orEmpty()
        .filterIsInstance<String>().filter(::isMidiName).sortedBy { it.lowercase() }.map { "$DEMO:/$it" }

    fun size(key: String): Long = resolve(key)?.let { path ->
        (files.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue
    } ?: -1L

    /** Reads a whole file, downloading it first when iCloud has only a placeholder. */
    fun readBytes(key: String): ByteArray {
        val path = resolve(key) ?: error(localized("The folder is no longer available", "フォルダにアクセスできません"))
        if (!files.fileExistsAtPath(path)) downloadFromICloud(path)
        val length = size(key)
        require(length <= 64L * 1024 * 1024) { localized("Choose a MIDI file of 64 MB or less", "64MB以下のMIDIファイルを選択してください") }
        val data = NSData.dataWithContentsOfFile(path) ?: error(localized("Cannot read ", "読み込めません: ") + fileName(key))
        return data.toByteArray()
    }

    /** Waits (up to 60 s) for an iCloud placeholder to become a real file. Call off the main thread. */
    private fun downloadFromICloud(path: String) {
        val url = NSURL.fileURLWithPath(path)
        files.startDownloadingUbiquitousItemAtURL(url, error = null)
        repeat(600) {
            if (files.fileExistsAtPath(path)) return
            NSThread.sleepForTimeInterval(0.1)
        }
        error(localized("The file is not available: ", "ファイルがありません: ") + url.lastPathComponent)
    }

    /** Bytes for metadata scans: only files already on the device (no iCloud downloads), up to 64 MB. */
    fun readBytesIfLocal(key: String): ByteArray? {
        val path = resolve(key) ?: return null
        if (!files.fileExistsAtPath(path) || size(key) > 64L * 1024 * 1024) return null
        return NSData.dataWithContentsOfFile(path)?.toByteArray()
    }

    private fun artworkFiles(dir: String): List<String> {
        val path = resolve(dir) ?: return emptyList()
        val names = files.contentsOfDirectoryAtPath(path, error = null).orEmpty().filterIsInstance<String>()
        return albumArtworkFileNames.flatMap { wanted -> names.filter { it.equals(wanted, ignoreCase = true) }.sorted() }
            .distinct().map { "$path/$it" }
    }

    /** Encoded folder artwork for the lock screen. */
    fun artworkData(dir: String): NSData? = artworkFiles(dir).firstNotNullOfOrNull { NSData.dataWithContentsOfFile(it) }

    /** Folder artwork (cover.jpg etc.), decoded at most 768 px wide like desktop. */
    fun artwork(dir: String): ImageBitmap? {
        for (file in artworkFiles(dir)) {
            val data = NSData.dataWithContentsOfFile(file) ?: continue
            val image = runCatching { Image.makeFromEncoded(data.toByteArray()) }.getOrNull() ?: continue
            return runCatching { downscale(image).toComposeImageBitmap() }.getOrNull()
        }
        return null
    }

    private fun downscale(image: Image): Image {
        val longest = maxOf(image.width, image.height)
        if (longest <= 768) return image
        val scale = 768f / longest
        val bitmap = org.jetbrains.skia.Bitmap()
        bitmap.allocN32Pixels((image.width * scale).toInt().coerceAtLeast(1), (image.height * scale).toInt().coerceAtLeast(1))
        val canvas = org.jetbrains.skia.Canvas(bitmap)
        try {
            canvas.drawImageRect(image, org.jetbrains.skia.Rect.makeWH(bitmap.width.toFloat(), bitmap.height.toFloat()))
        } finally {
            canvas.close()
        }
        return Image.makeFromBitmap(bitmap)
    }

    /** Renames a folder inside its parent and returns the new key. */
    fun rename(dir: String, name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed != "." && trimmed != ".." && '/' !in trimmed && ':' !in trimmed && !trimmed.startsWith('.')) {
            localized("Enter a valid folder name", "有効なフォルダ名を入力してください")
        }
        require(relative(dir).isNotEmpty()) { localized("This folder cannot be renamed", "このフォルダの名前は変更できません") }
        val source = resolve(dir) ?: error("Folder not found")
        val parentKey = parent(dir)!!
        val target = resolve(parentKey)!! + "/" + trimmed
        if (source == target) return dir
        require(!files.fileExistsAtPath(target)) { localized("A file or folder with that name already exists", "同じ名前のファイルまたはフォルダが既にあります") }
        nsError { files.moveItemAtPath(source, target, it) }
        return parentKey.trimEnd('/') + "/" + trimmed
    }

    /**
     * Copies a file from another app (Files "Open in", AirDrop, the picker) into Documents/Imported
     * unless it is already in the library, and returns its key.
     */
    fun importFile(url: NSURL): String {
        val path = url.path ?: error("Invalid file")
        keyForPath(path)?.let { return it }
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            val folder = "$documents/$IMPORT_FOLDER"
            files.createDirectoryAtPath(folder, withIntermediateDirectories = true, attributes = null, error = null)
            val name = url.lastPathComponent ?: "import.mid"
            val target = uniquePath(folder, name)
            nsError { files.copyItemAtPath(path, target, it) }
            return keyForPath(target)!!
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    private fun uniquePath(folder: String, name: String): String {
        var candidate = "$folder/$name"
        var index = 2
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        while (files.fileExistsAtPath(candidate)) candidate = "$folder/$stem ($index)$ext".also { index++ }
        return candidate
    }

    companion object {
        const val DOCUMENTS = "doc"
        const val DEMO = "demo"
        const val LINKED_PREFIX = "ext-"
        const val IMPORT_FOLDER = "Imported"
        private const val MAX_DEPTH = 12
        private const val MAX_FILES = 10000

        /** "/private/var/..." and "/var/..." name the same container files; compare them in one form. */
        fun normalizePath(path: String): String = path.trimEnd('/').let { if (it.startsWith("/private/var/")) it.removePrefix("/private") else it }

        fun isMidiName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in listOf("mid", "midi", "kar", "rmi", "smf")

        fun documentsLabel(): String {
            val device = UIDevice.currentDevice.model.substringBefore(' ').ifEmpty { "iPhone" }
            return localized("On My $device", "この${device}内")
        }
    }
}

/** Case-insensitive order with numbers compared by value ("2" before "10"), as the Files app sorts. */
private object NaturalOrder : Comparator<String> {
    @Suppress("CAST_NEVER_SUCCEEDS")
    override fun compare(a: String, b: String): Int =
        (a as NSString).localizedStandardCompare(b).toInt().coerceIn(-1, 1)
}

fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val result = ByteArray(size)
    if (size > 0) result.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return result
}

fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }

/** Runs a Foundation call that reports failure through NSError and throws its description. */
fun nsError(block: (CPointer<ObjCObjectVar<NSError?>>) -> Boolean) = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    if (!block(error.ptr)) throw IllegalStateException(error.value?.localizedDescription ?: "File operation failed")
}

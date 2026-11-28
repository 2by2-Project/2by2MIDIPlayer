@file:OptIn(ExperimentalForeignApi::class)

package jp.project2by2.musicplayer.ios

import jp.project2by2.musicplayer.soundfont.DlsBankConverter
import jp.project2by2.musicplayer.soundfont.SeekableFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.posix.EOF
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fgetc
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fputc
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.ftello
import platform.posix.ftruncate
import platform.posix.fwrite

/** Buffered stdio access for the shared DLS converter. [write] creates or truncates the file. */
class PosixSeekableFile(path: String, write: Boolean) : SeekableFile, AutoCloseable {
    private val file = fopen(path, if (write) "w+b" else "rb") ?: error("Cannot open ${path.substringAfterLast('/')}")

    override fun length(): Long {
        val position = ftello(file)
        fseeko(file, 0, SEEK_END)
        val length = ftello(file)
        fseeko(file, position, SEEK_SET)
        return length
    }

    override val filePointer: Long get() = ftello(file)

    override fun seek(position: Long) {
        check(fseeko(file, position, SEEK_SET) == 0) { "Seek failed" }
    }

    override fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        val read = buffer.usePinned { fread(it.addressOf(offset), 1u, length.toULong(), file) }
        if (read.toInt() != length) throw IllegalStateException("Unexpected end of file")
    }

    override fun readUnsignedByte(): Int {
        val value = fgetc(file)
        if (value == EOF) throw IllegalStateException("Unexpected end of file")
        return value
    }

    override fun write(value: Int) {
        check(fputc(value and 255, file) != EOF) { "Write failed" }
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        val written = bytes.usePinned { fwrite(it.addressOf(offset), 1u, length.toULong(), file) }
        check(written.toInt() == length) { "Write failed" }
    }

    override fun setLength(length: Long) {
        fflush(file)
        check(ftruncate(fileno(file), length) == 0) { "Cannot resize file" }
    }

    override fun close() {
        fclose(file)
    }
}

/**
 * A DLS bank at [path] becomes an SF2 next to it (the DLS is removed), as Android keeps the converted
 * bank. Other files are returned unchanged.
 */
fun convertDlsIfNeeded(path: String, checkpoint: () -> Unit): String {
    val dls = runCatching { PosixSeekableFile(path, false).use { DlsBankConverter.isDls(it) } }.getOrDefault(false)
    if (!dls) return path
    val output = path.substringBeforeLast('.') + "-dls.sf2"
    try {
        PosixSeekableFile(path, false).use { source ->
            PosixSeekableFile(output, true).use { target -> DlsBankConverter.convert(source, target, checkpoint) }
        }
    } catch (failure: Throwable) {
        NSFileManager.defaultManager.removeItemAtPath(output, null)
        throw failure
    }
    NSFileManager.defaultManager.removeItemAtPath(path, null)
    return output
}

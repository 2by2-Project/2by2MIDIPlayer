package jp.project2by2.musicplayer.soundfont

import java.io.File
import java.io.RandomAccessFile

/** DLS to SF2 conversion of files on Android and desktop JVMs; the converter itself is DlsBankConverter. */
object DlsConverter {
    fun isDls(file: File): Boolean = RandomAccessFile(file, "r").use { DlsBankConverter.isDls(JvmSeekableFile(it)) }

    /** Caller owns the new output; it is removed on any failure, including cancellation. */
    fun convert(input: File, output: File, checkpoint: () -> Unit = {}) {
        require(input.canonicalFile != output.canonicalFile) { "DLS input and SF2 output must differ" }
        try {
            RandomAccessFile(input, "r").use { source ->
                RandomAccessFile(output, "rw").use { target ->
                    DlsBankConverter.convert(JvmSeekableFile(source), JvmSeekableFile(target), checkpoint)
                }
            }
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }
}

private class JvmSeekableFile(private val file: RandomAccessFile) : SeekableFile {
    override fun length() = file.length()
    override val filePointer: Long get() = file.filePointer
    override fun seek(position: Long) = file.seek(position)
    override fun readFully(buffer: ByteArray, offset: Int, length: Int) = file.readFully(buffer, offset, length)
    override fun readUnsignedByte() = file.readUnsignedByte()
    override fun write(value: Int) = file.write(value)
    override fun write(bytes: ByteArray, offset: Int, length: Int) = file.write(bytes, offset, length)
    override fun setLength(length: Long) = file.setLength(length)
}

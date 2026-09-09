package jp.project2by2.musicplayer.soundfont

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** Returns a complete SF2 in a new file; never overwrites the active bank. Call off the UI thread. */
object SoundFontDownloader {
    fun download(url: String, directory: File, checkpoint: () -> Unit = {}, onProgress: (Float?) -> Unit = {}): File {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create SoundFont directory" }
        val file = File.createTempFile("download-", ".sf2", directory)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.getInputStream().use { input ->
                val length = connection.contentLengthLong
                var total = 0L
                file.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        checkpoint()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 1024L * 1024 * 1024) { "SoundFont exceeds 1 GiB" }
                        output.write(buffer, 0, count)
                        onProgress(if (length > 0) (total.toFloat() / length).coerceIn(0f, 1f) else null)
                    }
                }
                require(length < 0 || total == length) { "Incomplete SoundFont download" }
            }
            RandomAccessFile(file, "r").use {
                val header = ByteArray(12)
                it.readFully(header)
                require(header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                    header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "sfbk") { "Download is not a SoundFont" }
            }
            checkpoint()
            return file
        } catch (failure: Throwable) { file.delete(); throw failure }
        finally { connection.disconnect() }
    }
}

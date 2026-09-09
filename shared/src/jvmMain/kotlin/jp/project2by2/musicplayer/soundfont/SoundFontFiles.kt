package jp.project2by2.musicplayer.soundfont

import java.io.File

/** A converted file stays alive until the native font is freed. Source files are never deleted. */
class PreparedSoundFont internal constructor(val file: File, val converted: Boolean) : AutoCloseable {
    override fun close() { if (converted) file.delete() }
}

object SoundFontFiles {
    fun prepare(source: File, cacheDirectory: File, checkpoint: () -> Unit = {}): PreparedSoundFont {
        checkpoint()
        val isDls = DlsConverter.isDls(source)
        require(!source.extension.equals("dls", true) || isDls) { "Invalid DLS file" }
        if (!isDls) return PreparedSoundFont(source, false)
        check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) { "Cannot create SoundFont cache" }
        val output = File.createTempFile("dls-", ".sf2", cacheDirectory)
        DlsConverter.convert(source, output, checkpoint)
        return PreparedSoundFont(output, true)
    }
}

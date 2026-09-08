package jp.project2by2.musicplayer

import java.nio.charset.Charset
import kotlin.test.*

class MidiTextCodecTest {
    @Test fun japaneseMetadataKeepsAndroidCharsetSupport() {
        for (encoding in listOf("UTF-8", "MS932", "EUC-JP", "ISO-2022-JP")) {
            val text = "日本の音楽"
            val bytes = text.toByteArray(Charset.forName(encoding))
            assertEquals(text, parseMidiMetadata(metadataMidi(bytes, byteArrayOf())).title, encoding)
        }
    }
}

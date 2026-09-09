package jp.project2by2.musicplayer.desktop

import com.sun.net.httpserver.HttpServer
import jp.project2by2.musicplayer.soundfont.SoundFontDownloader
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.*

class SoundFontRecommendationTest {
    @Test fun windowsBankUsesSystemRootAndIsAbsentOnOtherPlatforms() {
        assertNull(windowsSoundFont("Linux", "/windows"))
        assertNull(windowsSoundFont("Android", null))
        assertEquals("gm.dls", windowsSoundFont("Windows 11", "D:/Windows")!!.name)
        assertEquals(java.io.File("D:/Windows", "System32/drivers/gm.dls"), windowsSoundFont("Windows 11", "D:/Windows"))
    }

    @Test fun downloadCompletesAndRejectsHtmlOrCancellationWithoutPartialFiles() {
        val directory = Files.createTempDirectory("font-download-").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val sf = testSoundFont()
        server.createContext("/bank.sf2") { request ->
            request.sendResponseHeaders(200, sf.size.toLong())
            request.responseBody.use { it.write(sf) }
        }
        server.createContext("/bad.sf2") { request ->
            val html = "<html>Not a SoundFont</html>".toByteArray()
            request.sendResponseHeaders(200, html.size.toLong())
            request.responseBody.use { it.write(html) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            var progress: Float? = null
            val complete = SoundFontDownloader.download("$base/bank.sf2", directory, onProgress = { progress = it })
            assertContentEquals(sf, complete.readBytes())
            assertEquals(1f, progress)
            assertFailsWith<IllegalArgumentException> { SoundFontDownloader.download("$base/bad.sf2", directory) }
            assertFailsWith<CancellationException> {
                SoundFontDownloader.download("$base/bank.sf2", directory, checkpoint = { throw CancellationException() })
            }
            assertEquals(listOf(complete), directory.listFiles()!!.toList())
        } finally {
            server.stop(0)
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
    }
}

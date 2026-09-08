package jp.project2by2.musicplayer.desktop

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class DesktopArtworkTest {
    @Test fun artworkUsesFolderNamesCachesAndRefreshesChangedImages() {
        val directory = Files.createTempDirectory("artwork").toFile()
        val cover = File(directory, "COVER.PNG")
        val fallback = File(directory, "folder.jpg")
        try {
            val loader = DesktopArtwork()
            assertNull(loader.load(directory))
            ImageIO.write(BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB), "png", cover)
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", fallback)
            val first = assertNotNull(loader.load(directory))
            assertEquals(32, first.width)
            assertSame(first, loader.load(directory))
            ImageIO.write(BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB), "png", cover)
            cover.setLastModified(cover.lastModified() + 2000)
            assertEquals(64, assertNotNull(loader.load(directory)).width)
            cover.writeText("corrupt image")
            assertEquals(8, assertNotNull(loader.load(directory)).width)
            fallback.delete()
            assertNull(loader.load(directory))
        } finally { cover.delete(); fallback.delete(); directory.delete() }
    }

    @Test fun largeArtworkIsDownsampledBeforeItReachesCompose() {
        val directory = Files.createTempDirectory("artwork-large").toFile()
        val cover = File(directory, "cover.jpg")
        try {
            val input = BufferedImage(2400, 1200, BufferedImage.TYPE_INT_RGB)
            try { ImageIO.write(input, "jpg", cover) } finally { input.flush() }
            val bitmap = assertNotNull(DesktopArtwork().load(directory))
            assertTrue(bitmap.width <= 768 && bitmap.height <= 768)
            assertEquals(2, bitmap.width / bitmap.height)
        } finally { cover.delete(); directory.delete() }
    }
}

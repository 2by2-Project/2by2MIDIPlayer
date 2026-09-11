package jp.project2by2.musicplayer.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlatformTest {
    @Test fun desktopOsNamesMapToSharedIdentity() {
        assertEquals(Platform.Windows, detectDesktopPlatform("Windows 11"))
        assertEquals(Platform.Windows, detectDesktopPlatform("windows 10"))
        assertEquals(Platform.Linux, detectDesktopPlatform("Linux"))
        assertFailsWith<IllegalStateException> { detectDesktopPlatform("Darwin") }
        assertFailsWith<IllegalStateException> { detectDesktopPlatform("Mac OS X") }
    }

    @Test fun desktopActualUsesHostOs() {
        assertEquals(detectDesktopPlatform(System.getProperty("os.name")), currentPlatform)
        assertTrue(currentPlatform.isDesktop)
    }
}

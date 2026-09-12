package jp.project2by2.musicplayer.desktop

import io.github.vinceglb.filekit.dialogs.FileKitDialogException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopFileDialogsTest {
    @Test fun titleBarCloseReturnsNoSelection() = runBlocking {
        val result = fileKitDialogResult<String> { throw FileKitPortalCompatibilityTest.closedPicker() }
        assertNull(result)
    }

    @Test fun selectedFilesAndOrdinaryCancellationPassThrough() = runBlocking {
        val files = listOf("日本語の 曲.mid", "second.midi")
        assertSame(files, fileKitDialogResult { files })
        assertNull(fileKitDialogResult<String> { null })
    }

    @Test fun coroutineCancellationAndOperationalFailuresPropagate() = runBlocking {
        val cancelled = CancellationException("Window disposed")
        assertSame(cancelled, assertFailsWith<CancellationException> {
            fileKitDialogResult<Nothing> { throw cancelled }
        })
        val failure = FileKitDialogException("Portal connection failed")
        assertSame(failure, assertFailsWith<FileKitDialogException> {
            fileKitDialogResult<Nothing> { throw failure }
        })
    }
}

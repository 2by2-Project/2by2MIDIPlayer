package jp.project2by2.musicplayer.desktop

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** Opens FileKit's OS-native picker from a Compose event coroutine. */
fun chooseFiles(
    scope: CoroutineScope,
    directory: Boolean = false,
    soundFont: Boolean = false,
    result: (List<File>) -> Unit,
    onError: (Exception) -> Unit,
) {
    scope.launch {
        try {
            val files = fileKitDialogResult {
                when {
                    directory -> listOfNotNull(FileKit.openDirectoryPicker()?.file)
                    soundFont -> listOfNotNull(
                        FileKit.openFilePicker(
                            type = FileKitType.File("sf2", "sf3", "sfz", "dls"),
                            mode = FileKitMode.Single,
                        )?.file,
                    )
                    else -> FileKit.openFilePicker(
                        type = FileKitType.File("mid", "midi"),
                        mode = FileKitMode.Multiple(),
                    )?.map { it.file }.orEmpty()
                }
            } ?: return@launch
            if (files.isNotEmpty()) result(files)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onError(failure)
        }
    }
}

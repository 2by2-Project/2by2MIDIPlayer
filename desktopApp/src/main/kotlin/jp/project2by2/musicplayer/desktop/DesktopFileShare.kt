package jp.project2by2.musicplayer.desktop

import androidx.compose.runtime.*
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openFileSaver
import jp.project2by2.musicplayer.ui.player.FileShareDialog
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.*

/** File-list clipboard flavor works with file managers and apps accepting pasted attachments. */
class MidiFileTransfer(private val file: File) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return listOf(file)
    }
}

fun exportMidiFile(source: File, target: File, overwrite: Boolean) {
    require(source.isFile) { "MIDIファイルが見つかりません: $source" }
    require(source.canonicalFile != target.canonicalFile) { "元ファイルとは別の保存先を選択してください" }
    if (overwrite) Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    else Files.copy(source.toPath(), target.toPath())
}

@Composable
fun DesktopShareDialog(file: File, onDismiss: () -> Unit) {
    var error by remember(file) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    FileShareDialog(file.name, error, onCopyFile = {
        runCatching {
            require(file.isFile) { "MIDIファイルが見つかりません: $file" }
            Toolkit.getDefaultToolkit().systemClipboard.setContents(MidiFileTransfer(file), null)
        }.onSuccess { onDismiss() }.onFailure { error = it.message ?: it.toString() }
    }, onExportFile = {
        scope.launch {
            try {
                val target = fileKitDialogResult {
                    FileKit.openFileSaver(
                        suggestedName = file.nameWithoutExtension,
                        defaultExtension = file.extension.lowercase().ifBlank { "mid" },
                        allowedExtensions = setOf("mid", "midi"),
                    )?.file
                } ?: return@launch
                // The native save dialog handles overwrite confirmation.
                val overwrite = target.exists()
                withContext(Dispatchers.IO) { exportMidiFile(file, target, overwrite) }
                onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: failure.toString()
            }
        }
    }, onDismiss = onDismiss)
}

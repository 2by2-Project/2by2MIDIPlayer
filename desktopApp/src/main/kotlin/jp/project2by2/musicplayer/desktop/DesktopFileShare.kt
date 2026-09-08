package jp.project2by2.musicplayer.desktop

import androidx.compose.runtime.*
import jp.project2by2.musicplayer.ui.player.FileShareDialog
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
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
        SwingUtilities.invokeLater {
            val chooser = JFileChooser().apply { selectedFile = File(file.name); dialogTitle = "MIDIファイルを書き出し" }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                val target = chooser.selectedFile
                val overwrite = target.exists()
                if (!overwrite || JOptionPane.showConfirmDialog(null, "${target.name} を上書きしますか？", "上書き確認", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { exportMidiFile(file, target, overwrite) } }
                            .onSuccess { onDismiss() }.onFailure { error = it.message ?: it.toString() }
                    }
                }
            }
        }
    }, onDismiss = onDismiss)
}

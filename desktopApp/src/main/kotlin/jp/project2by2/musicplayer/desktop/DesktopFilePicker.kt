package jp.project2by2.musicplayer.desktop

import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

fun chooseFiles(directory: Boolean = false, soundFont: Boolean = false, result: (List<File>) -> Unit) {
    SwingUtilities.invokeLater {
        val chooser = JFileChooser().apply {
            dialogTitle = if (soundFont) "SoundFontを選択" else if (directory) "MIDIフォルダを選択" else "MIDIファイルを追加"
            fileSelectionMode = if (directory) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = !directory && !soundFont
            if (!directory) fileFilter = if (soundFont) FileNameExtensionFilter("SoundFont / DLS", "sf2", "sf3", "sfz", "dls")
                else FileNameExtensionFilter("MIDI", "mid", "midi")
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            result(if (chooser.isMultiSelectionEnabled) chooser.selectedFiles.toList() else listOf(chooser.selectedFile))
        }
    }
}

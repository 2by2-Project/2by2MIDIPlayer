package jp.project2by2.musicplayer.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import java.awt.Dimension
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import javax.imageio.ImageIO
import java.io.File
import javax.swing.JOptionPane
import io.github.vinceglb.filekit.FileKit

private object DesktopIcon

fun main(args: Array<String>) {
    val files = args.map(::File)
    val instance = try {
        DesktopInstance.acquireOrForward(files)
    } catch (failure: Exception) {
        JOptionPane.showMessageDialog(null, failure.message, "2by2 MIDI Player", JOptionPane.ERROR_MESSAGE)
        return
    } ?: return
    instance.use {
        FileKit.init(appId = "jp.project2by2.musicplayer")
        runPlayer(files, it)
    }
}

private fun runPlayer(files: List<File>, instance: DesktopInstance) = application(exitProcessOnExit = false) {
    val controller = remember { DesktopController(startupFiles = files) }
    val appIcon = remember {
        DesktopIcon::class.java.getResourceAsStream("/app-icon.png").use { input ->
            BitmapPainter(ImageIO.read(requireNotNull(input)).toComposeImageBitmap())
        }
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    val windowState = rememberWindowState(width = 1280.dp, height = 720.dp)
    Window(onCloseRequest = { controller.close(); exitApplication() }, title = "2by2 MIDI Player", icon = appIcon,
        state = windowState) {
        window.minimumSize = Dimension(360, 560)
        LaunchedEffect(instance) {
            instance.requests.collect { files ->
                windowState.isMinimized = false
                window.toFront()
                window.requestFocus()
                controller.openLaunchFiles(files)
            }
        }
        _2by2MusicPlayerTheme { DesktopApp(controller) }
    }
}

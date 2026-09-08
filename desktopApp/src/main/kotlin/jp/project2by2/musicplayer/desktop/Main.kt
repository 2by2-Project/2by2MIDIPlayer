package jp.project2by2.musicplayer.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import java.awt.Dimension
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import javax.imageio.ImageIO

private object DesktopIcon

fun main() = application {
    val controller = remember { DesktopController() }
    val appIcon = remember {
        DesktopIcon::class.java.getResourceAsStream("/app-icon.png").use { input ->
            BitmapPainter(ImageIO.read(requireNotNull(input)).toComposeImageBitmap())
        }
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    Window(onCloseRequest = { controller.close(); exitApplication() }, title = "2by2 MIDI Player", icon = appIcon,
        state = rememberWindowState(width = 1280.dp, height = 720.dp)) {
        window.minimumSize = Dimension(360, 560)
        _2by2MusicPlayerTheme { DesktopApp(controller) }
    }
}

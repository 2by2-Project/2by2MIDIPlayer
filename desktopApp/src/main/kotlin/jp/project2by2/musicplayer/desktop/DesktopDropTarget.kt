package jp.project2by2.musicplayer.desktop

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI

private fun Transferable.uriListFlavor() = transferDataFlavors.firstOrNull {
    it.isMimeTypeEqual("text/uri-list") && it.representationClass == String::class.java
}

internal fun droppedFiles(data: Transferable): List<File> = when {
    data.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ->
        (data.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>().orEmpty()
    else -> data.uriListFlavor()?.let { flavor ->
        (data.getTransferData(flavor) as String).lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .mapNotNull { line -> runCatching { URI(line).takeIf { it.scheme == "file" }?.let(::File) }.getOrNull() }
            .toList()
    }.orEmpty()
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun Modifier.desktopFileDropTarget(controller: DesktopController): Modifier {
    val target = remember(controller) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean = try {
                controller.handleDroppedFiles(droppedFiles(event.awtTransferable))
            } catch (failure: Exception) {
                controller.reportError(failure)
                false
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            event.awtTransferable.let { it.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || it.uriListFlavor() != null }
        },
        target = target,
    )
}

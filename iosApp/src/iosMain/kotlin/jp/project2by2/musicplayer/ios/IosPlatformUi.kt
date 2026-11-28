@file:OptIn(ExperimentalForeignApi::class)

package jp.project2by2.musicplayer.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.downloadTaskWithURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.UniformTypeIdentifiers.UTTypeMIDI
import platform.darwin.NSObject

/** UIKit presentation for the Compose host: document pickers, the share sheet and the Files app. */
object IosPlatformUi {
    /** The Compose view controller, set when it is created. */
    var root: UIViewController? = null
    // The picker keeps only a weak reference to its delegate.
    private var delegate: PickerDelegate? = null

    private fun top(): UIViewController? {
        var controller = root ?: return null
        while (true) controller = controller.presentedViewController ?: return controller
    }

    private fun present(picker: UIDocumentPickerViewController, onPicked: (List<NSURL>) -> Unit) {
        val handler = PickerDelegate { urls ->
            delegate = null
            onPicked(urls)
        }
        delegate = handler
        picker.delegate = handler
        top()?.presentViewController(picker, animated = true, completion = null)
    }

    /** MIDI files from anywhere the Files app reaches; copies are imported into the library. */
    fun pickMidiFiles(onPicked: (List<NSURL>) -> Unit) {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeMIDI), asCopy = true)
        picker.allowsMultipleSelection = true
        present(picker, onPicked)
    }

    /** A folder that stays readable through a security-scoped bookmark (iCloud Drive, USB drives, ...). */
    fun pickFolder(onPicked: (NSURL) -> Unit) {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeFolder), asCopy = false)
        present(picker) { urls -> urls.firstOrNull()?.let(onPicked) }
    }

    /** SoundFonts have no system type; any file is accepted and its header is checked. */
    fun pickSoundFont(onPicked: (NSURL) -> Unit) {
        val types = listOfNotNull(UTType.typeWithFilenameExtension("sf2"), UTType.typeWithFilenameExtension("sf3"),
            UTType.typeWithFilenameExtension("dls"), UTTypeData)
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
        present(picker) { urls -> urls.firstOrNull()?.let(onPicked) }
    }

    /** Lets the user choose where a copy of [path] is saved; true saved, false failed, null cancelled. */
    fun exportFile(path: String, onResult: (Boolean?) -> Unit) {
        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(NSURL.fileURLWithPath(path)), asCopy = true)
        present(picker) { urls -> onResult(if (urls.isEmpty()) null else true) }
    }

    /** The system share sheet, Android's ACTION_SEND chooser. */
    fun share(path: String) {
        val host = top() ?: return
        val sheet = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(path)), applicationActivities = null)
        // iPad shows the sheet as a popover, which needs an anchor.
        sheet.popoverPresentationController?.let { popover ->
            popover.sourceView = host.view
            host.view.bounds.useContents { popover.sourceRect = CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0) }
            popover.permittedArrowDirections = 0u
        }
        host.presentViewController(sheet, animated = true, completion = null)
    }

    /** Opens a folder in the Files app (only folders inside this app's Documents are reachable). */
    fun openInFiles(path: String) {
        val url = NSURL.fileURLWithPath(path, isDirectory = true).absoluteString?.replaceFirst("file://", "shareddocuments://")
            ?.let { NSURL.URLWithString(it) } ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }
}

private class PickerDelegate(private val onPicked: (List<NSURL>) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onPicked(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onPicked(emptyList())
    }
}

/** Downloads [url] to [target] (replacing nothing) with progress; cancellation stops the transfer. */
suspend fun downloadFile(url: String, target: String, onProgress: (Float?) -> Unit) {
    val source = NSURL.URLWithString(url) ?: error("Invalid URL")
    val done = CompletableDeferred<Unit>()
    val task = NSURLSession.sharedSession.downloadTaskWithURL(source) { location, response, error ->
        val status = (response as? NSHTTPURLResponse)?.statusCode ?: 200L
        when {
            error != null || location == null -> done.completeExceptionally(IllegalStateException(error?.localizedDescription ?: "Download failed"))
            status !in 200L..299L -> done.completeExceptionally(IllegalStateException("HTTP $status"))
            // The temporary file is removed when this handler returns, so move it now.
            !NSFileManager.defaultManager.moveItemAtURL(location, NSURL.fileURLWithPath(target), null) ->
                done.completeExceptionally(IllegalStateException("Cannot save the download"))
            else -> done.complete(Unit)
        }
    }
    task.resume()
    try {
        while (!done.isCompleted) {
            val expected = task.countOfBytesExpectedToReceive
            val received = task.countOfBytesReceived
            check(received <= 1024L * 1024 * 1024) { "SoundFont exceeds 1 GiB" }
            onProgress(if (expected > 0) (received.toFloat() / expected).coerceIn(0f, 1f) else null)
            delay(200)
        }
        done.await()
    } finally {
        if (!done.isCompleted) task.cancel()
    }
}

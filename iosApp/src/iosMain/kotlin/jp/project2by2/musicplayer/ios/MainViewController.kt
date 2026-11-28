package jp.project2by2.musicplayer.ios

import androidx.compose.ui.window.ComposeUIViewController
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIViewController

/** One player for the app's lifetime; it outlives the Compose view controller like Android's service. */
val appController: IosController by lazy { IosController().also(::observeLifecycle) }

private fun observeLifecycle(controller: IosController) {
    val center = NSNotificationCenter.defaultCenter
    center.addObserverForName(UIApplicationDidBecomeActiveNotification, `object` = null, queue = NSOperationQueue.mainQueue) { _ ->
        controller.setForeground(true)
        // Files may have been added through the Files app or Finder meanwhile.
        controller.refreshLibrary()
    }
    center.addObserverForName(UIApplicationDidEnterBackgroundNotification, `object` = null, queue = NSOperationQueue.mainQueue) { _ ->
        controller.setForeground(false)
    }
}

/** Called by the Swift app (iosApp/xcode/App/MIDIPlayerApp.swift). */
fun MainViewController(): UIViewController = ComposeUIViewController {
    _2by2MusicPlayerTheme { IosApp(appController) }
}.also { IosPlatformUi.root = it }

/** MIDI files opened from other apps (Files "Open in", AirDrop, Mail) play as a temporary queue. */
fun openUrl(url: NSURL) {
    appController.openFiles(listOf(url))
}

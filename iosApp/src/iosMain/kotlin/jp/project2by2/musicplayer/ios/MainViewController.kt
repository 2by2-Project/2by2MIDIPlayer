package jp.project2by2.musicplayer.ios

import androidx.compose.ui.window.ComposeUIViewController
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIViewController
import kotlinx.coroutines.launch

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
}.also {
    IosPlatformUi.root = it
    startSmokePlayback(appController)
}

/**
 * CI check for Debug builds only: `-smokePlay <library key>` plays that file and prints the engine
 * position each second, so the simulator job can see BASS rendering advance.
 */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
private fun startSmokePlayback(controller: IosController) {
    if (!kotlin.native.Platform.isDebugBinary) return
    val arguments = platform.Foundation.NSProcessInfo.processInfo.arguments.filterIsInstance<String>()
    val key = arguments.getOrNull(arguments.indexOf("-smokePlay") + 1)?.takeIf { "-smokePlay" in arguments } ?: return
    controller.select(key, listOf(key), "Smoke test")
    kotlinx.coroutines.GlobalScope.launch {
        repeat(15) {
            kotlinx.coroutines.delay(1000)
            val state = controller.state.value
            println("smoke: current=${state.current} playing=${state.audio.playing} position=${state.audio.positionMs} " +
                "duration=${state.audio.durationMs} ready=${state.audioReady} message=${state.message?.text}")
        }
    }
}

/** MIDI files opened from other apps (Files "Open in", AirDrop, Mail) play as a temporary queue. */
fun openUrl(url: NSURL) {
    appController.openFiles(listOf(url))
}

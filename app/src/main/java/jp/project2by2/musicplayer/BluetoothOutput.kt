package jp.project2by2.musicplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import jp.project2by2.musicplayer.ui.player.BLUETOOTH_DELAY_FALLBACK_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val BLUETOOTH_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_HEARING_AID,
    TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST
)

// Wired outputs take priority over Bluetooth when both are connected.
private val WIRED_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_LINE_ANALOG
)

// AudioDeviceInfo.TYPE_BLE_* exist from API 31/33; their values are fixed, so use the literals on API 24+.
private const val TYPE_BLE_HEADSET = 26
private const val TYPE_BLE_SPEAKER = 27
private const val TYPE_BLE_BROADCAST = 30

/** True when media playback is currently routed to a Bluetooth output. */
fun isBluetoothMediaOutput(context: Context): Boolean {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
    val types = if (Build.VERSION.SDK_INT >= 33) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        audioManager.getAudioDevicesForAttributes(attributes).map { it.type }
    } else {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
    }
    return types.none { it in WIRED_OUTPUT_TYPES } && types.any { it in BLUETOOTH_OUTPUT_TYPES }
}

/**
 * Output latency the system reports for a media stream on the current route, in ms, or null when
 * unavailable. There is no public API for it; the hidden AudioTrack.getLatency() adds the HAL
 * (Bluetooth codec) delay to the track buffer. A track with the minimum buffer is used because
 * the BASS stream is of the same size (~230 ms), so the buffer is part of what the listener hears.
 */
private fun readOutputLatencyMs(): Int? = try {
    val sampleRate = 44100
    val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        )
        .setAudioFormat(
            AudioFormat.Builder().setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        )
        .setBufferSizeInBytes(minBuffer)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    try {
        (AudioTrack::class.java.getMethod("getLatency").invoke(track) as Int).takeIf { it in 1..3000 }
    } finally {
        track.release()
    }
} catch (e: Throwable) {
    null
}

/** Tracks whether media output is Bluetooth, following device connect/disconnect. */
@Composable
fun rememberBluetoothOutputActive(): Boolean {
    val context = LocalContext.current
    var active by remember { mutableStateOf(isBluetoothMediaOutput(context)) }
    DisposableEffect(context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                active = isBluetoothMediaOutput(context)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                active = isBluetoothMediaOutput(context)
            }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        active = isBluetoothMediaOutput(context)
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }
    return active
}

/**
 * Delay (ms) of the current Bluetooth output, or null when media is not going to Bluetooth.
 * Measured once per connection: re-measuring during playback let the value wobble and the roll with it.
 */
@Composable
fun rememberBluetoothOutputDelayMs(): Int? {
    val bluetooth = rememberBluetoothOutputActive()
    var delayMs by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(bluetooth) {
        delayMs = if (bluetooth) {
            withContext(Dispatchers.Default) { readOutputLatencyMs() } ?: BLUETOOTH_DELAY_FALLBACK_MS
        } else {
            null
        }
    }
    return if (bluetooth) delayMs else null
}

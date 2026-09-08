package jp.project2by2.musicplayer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class SettingsActivity : ComponentActivity() {
    private var boundService by mutableStateOf<PlaybackService?>(null)
    private var isBound = false

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as? PlaybackService.LocalBinder
            boundService = binder?.getService()
            isBound = (boundService != null)
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            isBound = false
            boundService = null
        }
    }

    override fun onStart() {
        super.onStart()
        // サービスが起動していない可能性があるなら startService → bind の順にすると堅い
        startService(Intent(this, PlaybackService::class.java))
        bindService(Intent(this, PlaybackService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        boundService = null
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            _2by2MusicPlayerTheme {
                SettingsScreen(playbackService = boundService)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(playbackService: PlaybackService?) {
    val context = LocalContext.current
    val activity = LocalActivity.current as Activity
    val scope = rememberCoroutineScope()
    val soundFontName by remember(context) {
        SettingsDataStore.soundFontNameFlow(context)
    }.collectAsState(initial = null)
    var hasSoundFont by remember { mutableStateOf(File(context.cacheDir, "soundfont.sf2").exists()) }

    val svc = playbackService

    var effectsEnabled by remember { mutableStateOf(false) }
    var reverbStrength by remember { mutableStateOf(1f) }

    val loopEnabled by SettingsDataStore.loopEnabledFlow(context).collectAsState(initial = false)
    val shuffleEnabled by SettingsDataStore.shuffleEnabledFlow(context).collectAsState(initial = false)

    var showSoundFontDialog by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(svc) {
        // Load settings
        effectsEnabled = SettingsDataStore.effectsEnabledFlow(context).first()
        reverbStrength = SettingsDataStore.reverbStrengthFlow(context).first()
    }

    fun resolveDisplayName(uri: Uri): String {
        // SAF / OpenDocument のURIなら基本ここで取れる
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }

        // fallback
        return uri.lastPathSegment?.substringAfterLast('/') ?: context.getString(R.string.unknown)
    }

    val soundFontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val cacheSoundFontFile = File(context.cacheDir, "soundfont.sf2")
            context.contentResolver.openInputStream(it)?.use { input ->
                cacheSoundFontFile.outputStream().use { output -> input.copyTo(output) }
            }
            hasSoundFont = cacheSoundFontFile.exists()
            val name = resolveDisplayName(it)
            scope.launch {
                SettingsDataStore.setSoundFontName(context, name)
            }
        }
    }


    val maxVoices by SettingsDataStore.maxVoicesFlow(context).collectAsState(initial = 40)
    jp.project2by2.musicplayer.ui.settings.SettingsScreen(
        soundFontName = soundFontName, hasSoundFont = hasSoundFont, maxVoices = maxVoices,
        effectsEnabled = effectsEnabled, reverbStrength = reverbStrength,
        loopEnabled = loopEnabled, shuffleEnabled = shuffleEnabled,
        onBack = { activity.finish() }, onPickSoundFont = { soundFontPicker.launch("application/octet-stream") },
        onRecommendedSoundFonts = { showSoundFontDialog = true },
        onMaxVoicesChange = { value -> svc?.setMaxVoices(value); scope.launch { SettingsDataStore.setMaxVoices(context, value) } },
        onEffectsChange = { value -> effectsEnabled = value; svc?.setEffectDisabled(!value); scope.launch { SettingsDataStore.setEffectsEnabled(context, value) } },
        onReverbChange = { value -> reverbStrength = value; svc?.setReverbStrength(value); scope.launch { SettingsDataStore.setReverbStrength(context, value) } },
        onLoopChange = { value -> scope.launch { SettingsDataStore.setLoopEnabled(context, value) } },
        onShuffleChange = { value -> scope.launch { SettingsDataStore.setShuffleEnabled(context, value) } }
    )
    if (showSoundFontDialog) {
        SoundFontDownloadDialog(
            onDismiss = { showSoundFontDialog = false },
            onDownloadComplete = {
                hasSoundFont = File(context.cacheDir, "soundfont.sf2").exists()
            }
        )
    }
}

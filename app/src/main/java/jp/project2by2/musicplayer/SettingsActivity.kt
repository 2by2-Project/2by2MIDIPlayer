package jp.project2by2.musicplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import jp.project2by2.musicplayer.soundfont.PreparedSoundFont
import jp.project2by2.musicplayer.soundfont.SoundFontFiles
import com.un4seen.bass.BASSMIDI
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
                AndroidSettingsScreen(playbackService = boundService, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AndroidSettingsScreen(playbackService: PlaybackService?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val soundFontName by remember(context) {
        SettingsDataStore.soundFontNameFlow(context)
    }.collectAsState(initial = null)
    var hasSoundFont by remember { mutableStateOf(File(context.cacheDir, "soundfont.sf2").exists()) }

    val svc = playbackService
    val currentService by androidx.compose.runtime.rememberUpdatedState(playbackService)

    var effectsEnabled by remember { mutableStateOf(false) }
    var reverbStrength by remember { mutableStateOf(1f) }

    val loopEnabled by SettingsDataStore.loopEnabledFlow(context).collectAsState(initial = false)
    val shuffleEnabled by SettingsDataStore.shuffleEnabledFlow(context).collectAsState(initial = false)

    var showSoundFontDialog by remember { mutableStateOf(false) }
    var soundFontLoading by remember { mutableStateOf(false) }
    jp.project2by2.musicplayer.ui.settings.SoundFontLoadingDialog(soundFontLoading)

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
        if (uri != null && !soundFontLoading) {
            soundFontLoading = true
            scope.launch {
                var imported: File? = null
                var prepared: PreparedSoundFont? = null
                var font = 0
                var runtimeAcquired = false
                try {
                    val name = withContext(Dispatchers.IO) {
                        val name = resolveDisplayName(uri)
                        val workContext = currentCoroutineContext()
                        val suffix = name.substringAfterLast('.', "sf2").lowercase().takeIf { it in listOf("dls", "sf2", "sf3") } ?: "sf2"
                        val source = File.createTempFile("soundfont-import-", ".$suffix", context.cacheDir)
                        imported = source
                        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open SoundFont")
                        input.use {
                            source.outputStream().use { output ->
                                val buffer = ByteArray(65536)
                                var total = 0L
                                while (true) {
                                    workContext.ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= 1024L * 1024 * 1024) { "SoundFont exceeds 1 GiB" }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        prepared = SoundFontFiles.prepare(source, context.cacheDir) { workContext.ensureActive() }
                        check(BassRuntime.acquire()) { "Cannot initialize audio engine" }
                        runtimeAcquired = true
                        font = BASSMIDI.BASS_MIDI_FontInit(prepared!!.file.absolutePath, 0)
                        check(font != 0) { "Cannot load SoundFont" }
                        name
                    }
                    currentCoroutineContext().ensureActive()
                    val commit = {
                        // Atomic replacement on Android, including API 24/25; never truncate the old bank.
                        android.system.Os.rename(prepared!!.file.absolutePath, File(context.cacheDir, "soundfont.sf2").absolutePath)
                    }
                    val service = currentService
                    if (service != null) {
                        if (service.installSoundFont(font, commit)) font = 0 // playback owns it now
                    } else commit()
                    hasSoundFont = true
                    SettingsDataStore.setSoundFontName(context, name)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    android.widget.Toast.makeText(context, context.getString(R.string.settings_soundfont_import_failed) + ": " + e.message,
                        android.widget.Toast.LENGTH_LONG).show()
                } finally {
                    if (font != 0) BASSMIDI.BASS_MIDI_FontFree(font)
                    if (runtimeAcquired) BassRuntime.release()
                    prepared?.close()
                    imported?.delete()
                    soundFontLoading = false
                }
            }
        }
    }


    val maxVoices by SettingsDataStore.maxVoicesFlow(context).collectAsState(initial = 40)
    jp.project2by2.musicplayer.ui.settings.SettingsScreen(
        soundFontName = soundFontName, hasSoundFont = hasSoundFont, maxVoices = maxVoices,
        soundFontLoading = soundFontLoading,
        effectsEnabled = effectsEnabled, reverbStrength = reverbStrength,
        loopEnabled = loopEnabled, shuffleEnabled = shuffleEnabled,
        onBack = onBack, onPickSoundFont = { soundFontPicker.launch("*/*") },
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

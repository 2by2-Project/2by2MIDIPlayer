package jp.project2by2.musicplayer.desktop

import jp.project2by2.musicplayer.PianoRollIndex
import jp.project2by2.musicplayer.parseSmfToPianoRollIndex
import jp.project2by2.musicplayer.MidiMetadata
import jp.project2by2.musicplayer.parseMidiMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

data class DesktopPlaylist(val id: String, val name: String, val paths: List<String> = emptyList())
data class DesktopState(
    val files: List<String> = emptyList(),
    val demoFiles: List<String> = emptyList(),
    val playlists: List<DesktopPlaylist> = emptyList(),
    val current: String? = null,
    val index: PianoRollIndex? = null,
    val audio: AudioPosition = AudioPosition(),
    val soundFont: String? = null,
    val volume: Float = 0.7f,
    val loop: Boolean = false,
    val shuffle: Boolean = false,
    val busy: Boolean = false,
    val audioReady: Boolean = false,
    val engine: String = "音声エンジンを準備中…",
    val error: String? = null,
    val metadata: Map<String, MidiMetadata> = emptyMap(),
    val maxVoices: Int = 40,
    val effectsEnabled: Boolean = false,
    val reverbStrength: Float = 1f,
)

/** Plain properties keep desktop settings separate from Android's database and DataStore. */
class DesktopStore(private val file: File = File(System.getProperty("user.home"), ".2by2MusicPlayer/desktop.properties")) {
    fun load(): DesktopState {
        if (!file.isFile) return DesktopState()
        val p = Properties().apply { file.inputStream().use(::load) }
        fun paths(prefix: String) = (0 until (p.getProperty("$prefix.count")?.toIntOrNull() ?: 0).coerceIn(0, 10000))
            .mapNotNull { p.getProperty("$prefix.$it") }.distinct()
        val lists = (0 until (p.getProperty("playlists.count")?.toIntOrNull() ?: 0).coerceIn(0, 1000)).mapNotNull { i ->
            val id = p.getProperty("playlists.$i.id") ?: return@mapNotNull null
            DesktopPlaylist(id, p.getProperty("playlists.$i.name", "プレイリスト"), paths("playlists.$i.tracks"))
        }
        return DesktopState(files = paths("files"), playlists = lists, soundFont = p.getProperty("soundFont"),
            volume = p.getProperty("volume")?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.7f,
            loop = p.getProperty("loop").toBoolean(), shuffle = p.getProperty("shuffle").toBoolean(),
            maxVoices = p.getProperty("maxVoices")?.toIntOrNull()?.coerceIn(1, 1000) ?: 40,
            effectsEnabled = p.getProperty("effectsEnabled").toBoolean(),
            reverbStrength = p.getProperty("reverbStrength")?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 3f) ?: 1f)
    }

    fun save(state: DesktopState) {
        val p = Properties()
        fun put(key: String, value: Any) { p.setProperty(key, value.toString()) }
        fun paths(prefix: String, paths: List<String>) {
            put("$prefix.count", paths.size)
            paths.forEachIndexed { i, path -> put("$prefix.$i", path) }
        }
        paths("files", state.files)
        put("playlists.count", state.playlists.size)
        state.playlists.forEachIndexed { i, list ->
            put("playlists.$i.id", list.id); put("playlists.$i.name", list.name); paths("playlists.$i.tracks", list.paths)
        }
        state.soundFont?.let { put("soundFont", it) }
        put("volume", state.volume); put("loop", state.loop); put("shuffle", state.shuffle)
        put("maxVoices", state.maxVoices); put("effectsEnabled", state.effectsEnabled); put("reverbStrength", state.reverbStrength)
        file.parentFile.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.outputStream().use { p.store(it, "2by2 Music Player desktop") }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

class DesktopController(
    private val store: DesktopStore = DesktopStore(),
    val midiFiles: DesktopMidiFiles = DesktopMidiFiles(),
    private val engine: BassAudio = BassAudio()
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "desktop-audio").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    // Construct shutdown work while the controller is loaded, not during window disposal.
    private val shutdownTask = FutureTask<Unit> { engine.close() }
    private var closed = false
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val metadataReader = DesktopMetadataReader()
    private val mutable = MutableStateFlow(DesktopState())
    val state = mutable.asStateFlow()
    private var loaded: String? = null
    private var queue = emptyList<String>()
    private val trackIds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val nextTrackId = java.util.concurrent.atomic.AtomicLong()
    fun trackId(path: String): Long = trackIds.computeIfAbsent(path) { nextTrackId.incrementAndGet() }

    init {
        operation {
            mutable.value = store.load()
            refreshMetadata(mutable.value.files + mutable.value.playlists.flatMap { it.paths })
            val status = engine.initialize()
            mutable.update { it.copy(audioReady = true, engine = status) }
            engine.loop(mutable.value.loop)
            applySynthSettings()
            mutable.value.soundFont?.let { engine.setSoundFont(File(it)) }
        }
        scope.launch {
            while (isActive) {
                delay(40)
                try {
                    val previous = mutable.value.audio
                    val next = if (loaded == null) AudioPosition(durationMs = mutable.value.index?.totalDurationMs ?: 0) else engine.position()
                    mutable.update { it.copy(audio = next) }
                    if (previous.playing && !next.playing && next.durationMs > 0 && next.positionMs >= next.durationMs - 100 && !mutable.value.loop) {
                        advance(1, automatic = true)
                    }
                } catch (e: Exception) { mutable.update { it.copy(error = e.message) } }
            }
        }
    }

    private fun operation(block: () -> Unit) = scope.launch {
        try { block() } catch (e: Exception) { mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName) } }
        catch (e: LinkageError) { mutable.update { it.copy(error = "音声ライブラリを読み込めません: ${e.message}", engine = "音声エンジン未接続") } }
        finally { mutable.update { it.copy(busy = false) } }
    }
    private fun save() = store.save(mutable.value)
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun importFiles(files: List<File>) = operation {
        mutable.update { it.copy(busy = true) }
        val found = files.flatMap { file ->
            if (file.isDirectory) Files.walk(file.toPath(), 12).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().substringAfterLast('.', "").lowercase() in listOf("mid", "midi") }
                    .limit(10000).map { it.toFile().canonicalPath }.toList()
            } else if (file.extension.lowercase() in listOf("mid", "midi")) listOf(file.canonicalPath) else emptyList()
        }
        mutable.update { it.copy(files = (it.files + found).distinct().sortedBy { path -> File(path).name.lowercase() }) }
        refreshMetadata(found)
        save()
    }
    fun select(path: String, playbackQueue: List<String>) = operation {
        queue = playbackQueue.toList()
        loadTrack(path, autoplay = mutable.value.soundFont != null && mutable.value.audioReady)
    }
    fun loadDemos() = operation {
        mutable.update { it.copy(busy = true) }
        val demos = midiFiles.demos()
        mutable.update { it.copy(demoFiles = demos) }
        refreshMetadata(demos)
    }
    private fun loadTrack(path: String, autoplay: Boolean) {
        mutable.update { it.copy(busy = true, error = null) }
        val file = midiFiles.resolve(path)
        require(file.length() <= 64L * 1024 * 1024) { "64MB以下のMIDIファイルを選択してください" }
        val index = parseSmfToPianoRollIndex(file.readBytes()) ?: error("対応するMIDIファイルではありません: ${file.name}")
        require(index.totalTicks > 0) { "MIDIに再生可能な時間情報がありません" }
        engine.unload()
        loaded = null
        mutable.update { it.copy(current = path, index = index, audio = AudioPosition(durationMs = index.totalDurationMs)) }
        if (autoplay) {
            engine.load(file, index.loopPointTick, mutable.value.volume)
            loaded = path
            engine.play()
        }
        mutable.update { it.copy(busy = false) }
    }
    fun togglePlay() = operation {
        val value = mutable.value
        if (value.audio.playing) engine.pause() else {
            val path = value.current ?: return@operation
            if (loaded != path) { engine.load(midiFiles.resolve(path), value.index?.loopPointTick, value.volume); loaded = path }
            engine.play()
        }
        mutable.update { it.copy(audio = engine.position()) }
    }
    fun seek(ms: Long) = operation {
        if (loaded != mutable.value.current || loaded == null) return@operation
        engine.seek(ms.coerceIn(0, mutable.value.audio.durationMs.coerceAtLeast(0)))
        mutable.update { it.copy(audio = engine.position()) }
    }
    fun setFont(file: File) = operation {
        engine.setSoundFont(file)
        mutable.update { it.copy(soundFont = file.canonicalPath, audioReady = true) }
        save()
    }
    fun setVolume(volume: Float) = operation {
        engine.volume(volume)
        mutable.update { it.copy(volume = volume) }; save()
    }
    fun toggleLoop() = operation {
        val enabled = !mutable.value.loop
        engine.loop(enabled); mutable.update { it.copy(loop = enabled) }; save()
    }
    fun toggleShuffle() = operation { mutable.update { it.copy(shuffle = !it.shuffle) }; save() }
    private fun refreshMetadata(paths: List<String>) = scope.launch(Dispatchers.IO) {
        // Parsing a large library must not delay native playback or the position polling loop.
        paths.distinct().forEach { path ->
            ensureActive()
            runCatching { metadataReader.read(midiFiles.resolve(path)) }.onSuccess { metadata ->
                mutable.update { it.copy(metadata = it.metadata + (path to metadata)) }
            }
        }
    }
    private fun applySynthSettings() = mutable.value.let { engine.synthSettings(it.maxVoices, it.effectsEnabled, it.reverbStrength) }
    fun setMaxVoices(value: Int) = operation { mutable.update { it.copy(maxVoices = value.coerceIn(1, 1000)) }; applySynthSettings(); save() }
    fun setEffectsEnabled(value: Boolean) = operation { mutable.update { it.copy(effectsEnabled = value) }; applySynthSettings(); save() }
    fun setReverbStrength(value: Float) = operation { require(value.isFinite()); mutable.update { it.copy(reverbStrength = value.coerceIn(0f, 3f)) }; applySynthSettings(); save() }
    fun renamePlaylist(id: String, name: String) = operation {
        if (name.isNotBlank()) { mutable.update { it.copy(playlists = it.playlists.map { list -> if (list.id == id) list.copy(name = name.trim()) else list }) }; save() }
    }
    fun deletePlaylist(id: String) = operation { mutable.update { it.copy(playlists = it.playlists.filterNot { list -> list.id == id }) }; save() }
    fun movePlaylistTrack(id: String, path: String, delta: Int) = operation {
        mutable.update { it.copy(playlists = it.playlists.map { list ->
            if (list.id != id) list else {
                val paths = list.paths.toMutableList()
                val from = paths.indexOf(path)
                if (from >= 0) paths.add((from + delta).coerceIn(0, paths.lastIndex), paths.removeAt(from))
                list.copy(paths = paths)
            }
        }) }; save()
    }
    fun next(direction: Int) = operation { advance(direction, automatic = false) }
    fun queueNext(path: String) = operation {
        if (mutable.value.current == null) {
            queue = listOf(path)
            loadTrack(path, autoplay = mutable.value.soundFont != null)
        } else {
            val current = queue.indexOf(mutable.value.current)
            queue = queue.toMutableList().apply { add((current + 1).coerceIn(0, size), path) }
        }
    }
    private fun advance(direction: Int, automatic: Boolean) {
        if (queue.isEmpty()) return
        val current = queue.indexOf(mutable.value.current)
        if (automatic && !mutable.value.shuffle && current == queue.lastIndex) return
        val path = if (mutable.value.shuffle && direction > 0) queue.filter { it != mutable.value.current }.randomOrNull() ?: queue.first()
            else queue[Math.floorMod(current + direction, queue.size)]
        loadTrack(path, autoplay = mutable.value.soundFont != null)
    }
    fun createPlaylist(name: String) = operation {
        if (name.isBlank()) return@operation
        mutable.update { it.copy(playlists = it.playlists + DesktopPlaylist(UUID.randomUUID().toString(), name.trim())) }; save()
    }
    fun addToPlaylist(path: String, id: String?) = operation {
        var lists = mutable.value.playlists
        if (lists.isEmpty()) lists = listOf(DesktopPlaylist(UUID.randomUUID().toString(), "お気に入り"))
        val target = id ?: lists.first().id
        mutable.update { it.copy(playlists = lists.map { list -> if (list.id == target) list.copy(paths = (list.paths + path).distinct()) else list }) }; save()
    }
    fun removeFromPlaylist(path: String, id: String) = operation {
        mutable.update { it.copy(playlists = it.playlists.map { list -> if (list.id == id) list.copy(paths = list.paths - path) else list }) }; save()
    }
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        try {
            // Queue after in-flight native calls on the same BASS device thread.
            executor.execute(shutdownTask)
            shutdownTask.get()
        } finally {
            dispatcher.close()
        }
    }
}

fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

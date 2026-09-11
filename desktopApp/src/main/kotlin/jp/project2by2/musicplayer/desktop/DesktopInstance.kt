package jp.project2by2.musicplayer.desktop

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** One audio engine per user. The OS releases the lock even after a process crash. */
internal class DesktopInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
    private val token: String,
) : AutoCloseable {
    private val pending = Channel<List<File>>(Channel.UNLIMITED)
    val requests = pending.receiveAsFlow()
    @Volatile private var closed = false
    private val listener = thread(name = "desktop-launch-requests", isDaemon = true) {
        // A retry after a lost acknowledgement must not restart the same track twice.
        val accepted = LinkedHashSet<String>()
        while (!closed) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val input = DataInputStream(socket.getInputStream())
                    require(input.readUTF() == token) { "Invalid launch token" }
                    val id = input.readUTF()
                    val count = input.readInt()
                    require(count in 0..MAX_FILES) { "Too many launch files" }
                    val files = List(count) { File(input.readUTF()) }
                    if (id !in accepted) {
                        check(pending.trySend(files).isSuccess)
                        accepted.add(id)
                        if (accepted.size > 1_024) accepted.remove(accepted.first())
                    }
                    DataOutputStream(socket.getOutputStream()).apply { writeBoolean(true); flush() }
                }
            } catch (failure: Exception) {
                if (!closed) System.err.println("Launch request rejected: ${failure.message}")
            }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        server.close()
        listener.join(3_000)
        pending.close()
        try { lock.release() } finally { channel.close() }
        // Never delete the lock file: another process may already have opened its inode.
        // A stale endpoint is harmless; the next lock owner replaces it before listening.
    }

    companion object {
        private const val MAX_FILES = 1_024
        private val loopback = InetAddress.getByName("127.0.0.1")

        /** Returns null only after an existing instance has accepted the request. */
        fun acquireOrForward(
            files: List<File>,
            directory: File = File(System.getProperty("user.home"), ".2by2MusicPlayer"),
            timeoutMillis: Long = 10_000,
        ): DesktopInstance? {
            require(files.size <= MAX_FILES) { "一度に開くファイルは${MAX_FILES}個以下にしてください" }
            // Resolve relative paths in the launching process, before crossing process boundaries.
            val paths = files.map { it.absoluteFile.normalize().path }
            directory.mkdirs()
            check(directory.isDirectory) { "起動情報の保存先を作成できません: $directory" }
            val channel = FileChannel.open(File(directory, "instance.lock").toPath(), CREATE, WRITE)
            val endpoint = File(directory, "instance.endpoint")
            val id = UUID.randomUUID().toString()
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            var lastFailure: Exception? = null
            try {
                do {
                    val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock != null) {
                        val server = ServerSocket()
                        try {
                            server.bind(InetSocketAddress(loopback, 0))
                            val token = UUID.randomUUID().toString()
                            // Separate from the lock file because Windows locks also restrict reads.
                            endpoint.outputStream().use { stream ->
                                DataOutputStream(stream).apply { writeInt(server.localPort); writeUTF(token) }
                            }
                            return DesktopInstance(channel, lock, server, token)
                        } catch (failure: Exception) {
                            server.close()
                            lock.release()
                            throw failure
                        }
                    }
                    try {
                        val (port, token) = DataInputStream(endpoint.inputStream()).use { it.readInt() to it.readUTF() }
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(loopback, port), 500)
                            socket.soTimeout = 2_000
                            DataOutputStream(socket.getOutputStream()).apply {
                                writeUTF(token)
                                writeUTF(id)
                                writeInt(paths.size)
                                paths.forEach(::writeUTF)
                                flush()
                            }
                            check(DataInputStream(socket.getInputStream()).readBoolean())
                        }
                        channel.close()
                        return null
                    } catch (failure: Exception) {
                        lastFailure = failure
                    }
                    Thread.sleep(100)
                } while (System.nanoTime() < deadline)
                throw IOException("起動済みのアプリにファイルを渡せませんでした。しばらく待ってからもう一度開いてください。", lastFailure)
            } catch (failure: Exception) {
                channel.close()
                throw failure
            }
        }
    }
}

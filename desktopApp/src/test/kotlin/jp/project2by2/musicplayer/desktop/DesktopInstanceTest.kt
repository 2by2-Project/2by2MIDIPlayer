package jp.project2by2.musicplayer.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class DesktopInstanceTest {
    private fun fixture(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("desktop-instance").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun forwardsUnicodePathsBeforeUiStartsAndPreservesRepeatedRequests() = fixture { directory ->
        assertNotNull(DesktopInstance.acquireOrForward(emptyList(), directory)).use { primary ->
            val files = listOf(File("日本語の 曲.MID"), File(directory, "two words.midi"))
            repeat(2) { assertNull(DesktopInstance.acquireOrForward(files, directory)) }
            assertNull(DesktopInstance.acquireOrForward(emptyList(), directory))
            val received = runBlocking { withTimeout(5_000) { primary.requests.take(3).toList() } }
            assertEquals(listOf(files.map { it.absoluteFile.normalize() }, files.map { it.absoluteFile.normalize() }, emptyList()), received)
        }
    }

    @Test fun concurrentLaunchesElectOnlyOneOwner() = fixture { directory ->
        val results = runBlocking {
            (1..8).map { async(kotlinx.coroutines.Dispatchers.IO) {
                DesktopInstance.acquireOrForward(listOf(File("$it.mid")), directory)
            } }.map { it.await() }
        }
        val owners = results.filterNotNull()
        try {
            assertEquals(1, owners.size)
            val received = runBlocking { withTimeout(5_000) { owners.single().requests.take(7).toList() } }
            assertEquals(7, received.flatten().distinct().size)
        } finally { owners.forEach { it.close() } }
    }

    @Test fun cleanShutdownAllowsRestartDespiteStaleEndpoint() = fixture { directory ->
        assertNotNull(DesktopInstance.acquireOrForward(emptyList(), directory)).use { it.close() }
        assertNotNull(DesktopInstance.acquireOrForward(emptyList(), directory)).use { primary ->
            assertNull(DesktopInstance.acquireOrForward(listOf(File("restart.mid")), directory))
            val received = runBlocking { withTimeout(5_000) { primary.requests.first() } }
            assertEquals("restart.mid", received.single().name)
        }
    }

    @Test fun unavailableOwnerReportsFailureInsteadOfStartingAnotherPlayer() = fixture { directory ->
        java.nio.channels.FileChannel.open(File(directory, "instance.lock").toPath(),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                assertFailsWith<java.io.IOException> {
                    DesktopInstance.acquireOrForward(listOf(File("waiting.mid")), directory, timeoutMillis = 200)
                }
            }
        }
    }

    @Test fun anotherProcessForwardsAndCrashReleasesLock() = fixture { directory ->
        val javaExecutable = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = listOf(
            DesktopInstanceProcess::class.java, DesktopInstance::class.java,
            kotlin.Unit::class.java, kotlinx.coroutines.channels.Channel::class.java,
        ).map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        fun process(mode: String) = ProcessBuilder(javaExecutable, "-cp", classpath,
            DesktopInstanceProcess::class.java.name, directory.path, mode).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        assertNotNull(DesktopInstance.acquireOrForward(emptyList(), directory)).use { primary ->
            val child = process("forward")
            try {
                assertTrue(child.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, child.exitValue())
                val received = runBlocking { withTimeout(5_000) { primary.requests.first() } }
                assertEquals("別プロセスの 曲.mid", received.single().name)
            } finally { child.destroyForcibly() }
        }
        val owner = process("own")
        try {
            val ready = java.util.concurrent.CompletableFuture.supplyAsync { owner.inputStream.bufferedReader().readLine() }
            assertEquals("ready", ready.get(10, TimeUnit.SECONDS))
        } finally {
            owner.destroyForcibly()
            assertTrue(owner.waitFor(10, TimeUnit.SECONDS))
        }
        assertNotNull(DesktopInstance.acquireOrForward(emptyList(), directory)).close()
    }
}

/** Separate JVM, without Compose or BASS, to exercise actual OS lock ownership. */
object DesktopInstanceProcess {
    @JvmStatic fun main(args: Array<String>) {
        val owner = DesktopInstance.acquireOrForward(listOf(File("別プロセスの 曲.mid")), File(args[0]))
        if (args[1] == "forward") {
            check(owner == null)
        } else {
            check(owner != null)
            println("ready")
            System.out.flush()
            java.util.concurrent.CountDownLatch(1).await()
        }
    }
}

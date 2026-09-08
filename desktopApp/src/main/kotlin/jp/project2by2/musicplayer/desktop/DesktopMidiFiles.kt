package jp.project2by2.musicplayer.desktop

import java.io.File

/** Stable demo identities also survive moving an app image or upgrading its directory. */
class DesktopMidiFiles(private val demoDirectory: File = defaultDemoDirectory()) {
    fun demos(): List<String> {
        check(demoDirectory.isDirectory) { "サンプルMIDIが見つかりません: $demoDirectory" }
        return demoDirectory.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in listOf("mid", "midi") }
            .sortedBy { it.name }.map { DEMO_PREFIX + it.name }
    }
    fun resolve(id: String): File {
        if (!isDemo(id)) return File(id)
        val name = id.removePrefix(DEMO_PREFIX)
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name) { "Invalid demo file name" }
        return File(demoDirectory, name)
    }
    companion object {
        private const val DEMO_PREFIX = "demo://"
        fun isDemo(id: String) = id.startsWith(DEMO_PREFIX)
        fun fileName(id: String) = if (isDemo(id)) id.removePrefix(DEMO_PREFIX) else File(id).name
        private fun defaultDemoDirectory() = System.getProperty("midi.demo.dir")?.let(::File)
            ?: System.getProperty("compose.application.resources.dir")?.let { File(it, "demo") }
            ?: File("app/src/main/assets/demo")
    }
}

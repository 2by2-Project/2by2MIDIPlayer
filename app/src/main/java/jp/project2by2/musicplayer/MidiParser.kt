package jp.project2by2.musicplayer

import android.content.ContentResolver
import android.net.Uri
import dev.atsushieno.ktmidi.Midi1CompoundMessage
import dev.atsushieno.ktmidi.Midi1Music
import dev.atsushieno.ktmidi.MidiChannelStatus
import dev.atsushieno.ktmidi.read
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class MidiMetadata(
    val title: String?,
    val copyright: String?,
    val loopPointMs: Long?,
    val durationMs: Long?
)

class MidiParser(private val contentResolver: ContentResolver) {
    fun getMetadata(uri: Uri): MidiMetadata {
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return EMPTY_METADATA
        return getMetadataFromBytes(bytes)
    }

    fun getMetadataFromFile(file: File): MidiMetadata {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return EMPTY_METADATA
        return getMetadataFromBytes(bytes)
    }

    private fun getMetadataFromBytes(bytes: ByteArray): MidiMetadata {
        return runCatching {
            val music = Midi1Music().apply { read(bytes.toList()) }
            MidiMetadataExtractor.extract(music)
        }.getOrElse {
            EMPTY_METADATA
        }
    }

    private object MidiMetadataExtractor {
        private data class Candidate(val trackIndex: Int, val tick: Int, val text: String)

        fun extract(music: Midi1Music): MidiMetadata {
            val titleCandidates = mutableListOf<Candidate>()
            val copyrightCandidates = mutableListOf<Candidate>()
            var maxTick = 0
            var loopStartTick: Int? = null

            for ((trackIndex, track) in music.tracks.withIndex()) {
                var tick = 0
                for (event in track.events) {
                    tick += event.deltaTime
                    if (tick > maxTick) maxTick = tick

                    val msg = event.message
                    val status = msg.statusByte.toInt() and 0xF0
                    if (status == MidiChannelStatus.CC && msg.msb.toInt() == 111) {
                        loopStartTick = minOf(loopStartTick ?: tick, tick)
                    }

                    if ((msg.statusByte.toInt() and 0xFF) != 0xFF) continue
                    val metaType = msg.msb.toInt() and 0xFF
                    val data = (msg as? Midi1CompoundMessage)?.extraData ?: continue
                    val text = decodeMidiMetaText(data) ?: continue
                    val candidate = Candidate(trackIndex = trackIndex, tick = tick, text = text)

                    when (metaType) {
                        0x03 -> titleCandidates.add(candidate)
                        0x02 -> copyrightCandidates.add(candidate)
                    }
                }
            }

            // Title is accepted only from track 0 at tick 0.
            val title = titleCandidates
                .firstOrNull { it.trackIndex == 0 && it.tick == 0 }
                ?.text
            val copyright = copyrightCandidates
                .sortedBy { it.tick }
                .firstOrNull()
                ?.text
            val durationMs = music.getTimePositionInMillisecondsForTick(maxTick).toLong()
            val loopPointMs = loopStartTick?.let { music.getTimePositionInMillisecondsForTick(it).toLong() }

            return MidiMetadata(
                title = title,
                copyright = copyright,
                loopPointMs = loopPointMs,
                durationMs = durationMs
            )
        }

        private fun decodeMidiMetaText(data: ByteArray): String? {
            if (data.isEmpty()) return null
            val candidates = listOf(
                decodeWithCharsetOrNull(data, Charsets.UTF_8),
                decodeWithCharsetOrNull(data, Charset.forName("MS932")),
                decodeWithCharsetOrNull(data, Charset.forName("EUC-JP")),
                decodeWithCharsetOrNull(data, Charset.forName("ISO-2022-JP")),
                decodeWithCharsetOrNull(data, Charsets.ISO_8859_1)
            ).filterNotNull().map { it.trim().replace('\u0000', ' ') }.filter { it.isNotBlank() }

            if (candidates.isEmpty()) return null
            return candidates.maxByOrNull { scoreDecodedText(it) }
        }

        // Backward compatibility for older ktmidi variants where extraData was List<Byte>.
        private fun decodeMidiMetaText(data: List<Byte>): String? = decodeMidiMetaText(data.toByteArray())

        private fun decodeWithCharsetOrNull(data: ByteArray, charset: Charset): String? {
            return try {
                val decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                decoder.decode(ByteBuffer.wrap(data)).toString()
            } catch (_: CharacterCodingException) {
                null
            }
        }

        private fun scoreDecodedText(text: String): Int {
            var score = 0
            var jpCount = 0
            var replacementCount = 0
            var controlCount = 0
            for (ch in text) {
                when {
                    ch == '\uFFFD' -> replacementCount++
                    ch.isISOControl() && ch != '\n' && ch != '\r' && ch != '\t' -> controlCount++
                    ch in '\u3040'..'\u30FF' || ch in '\u4E00'..'\u9FFF' -> jpCount++
                }
            }
            score += jpCount * 3
            score -= replacementCount * 10
            score -= controlCount * 5
            if (text.any { it.isLetterOrDigit() }) score += 5
            return score
        }
    }

    private companion object {
        val EMPTY_METADATA = MidiMetadata(
            title = null,
            copyright = null,
            loopPointMs = null,
            durationMs = null
        )
    }
}

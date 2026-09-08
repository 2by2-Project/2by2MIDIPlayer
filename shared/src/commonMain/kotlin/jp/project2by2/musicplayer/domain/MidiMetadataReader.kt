package jp.project2by2.musicplayer

import dev.atsushieno.ktmidi.Midi1CompoundMessage
import dev.atsushieno.ktmidi.Midi1Music
import dev.atsushieno.ktmidi.MidiChannelStatus
import dev.atsushieno.ktmidi.read

/** The Android metadata policy, shared verbatim with desktop; byte access stays in hosts. */
fun parseMidiMetadata(bytes: ByteArray): MidiMetadata = runCatching {
    MidiMetadataExtractor.extract(Midi1Music().apply { read(bytes.toList()) })
}.getOrElse { MidiMetadata(null, null, null, null) }

internal expect fun decodeWithCharsetOrNull(data: ByteArray, charset: String): String?

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
                decodeWithCharsetOrNull(data, "UTF-8"),
                decodeWithCharsetOrNull(data, "MS932"),
                decodeWithCharsetOrNull(data, "EUC-JP"),
                decodeWithCharsetOrNull(data, "ISO-2022-JP"),
                decodeWithCharsetOrNull(data, "ISO-8859-1")
            ).filterNotNull().map { it.trim().replace('\u0000', ' ') }.filter { it.isNotBlank() }

            if (candidates.isEmpty()) return null
            return candidates.maxByOrNull { scoreDecodedText(it) }
        }

        // Backward compatibility for older ktmidi variants where extraData was List<Byte>.
        private fun decodeMidiMetaText(data: List<Byte>): String? = decodeMidiMetaText(data.toByteArray())

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


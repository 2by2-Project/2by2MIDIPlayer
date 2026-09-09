package jp.project2by2.musicplayer.soundfont

import java.io.File
import java.io.RandomAccessFile

/** Streaming Kotlin DLS reader/SF2 writer for Android and desktop JVMs.
 * Conversion rules based on stuerp/libsf (MIT, see THIRD_PARTY_NOTICES.md).
 * No native codec, Windows API, or whole-bank sample allocation is needed.
 */
object DlsConverter {
    fun isDls(file: File): Boolean = RandomAccessFile(file, "r").use {
        it.length() >= 12 && it.tag() == "RIFF" && run { it.seek(8); it.tag() == "DLS " }
    }

    /** Caller owns the new output; it is removed on any failure, including cancellation. */
    fun convert(input: File, output: File, checkpoint: () -> Unit = {}) {
        require(input.canonicalFile != output.canonicalFile) { "DLS input and SF2 output must differ" }
        try {
            RandomAccessFile(input, "r").use { source ->
                val reader = Reader(source, checkpoint)
                val bank = reader.read()
                RandomAccessFile(output, "rw").use { target ->
                    target.setLength(0)
                    writeBank(source, target, bank, checkpoint)
                }
            }
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }

    private data class Chunk(val id: String, val start: Long, val end: Long, val type: String = "")
    private data class Loop(val type: Int, val start: Int, val length: Int)
    private data class Sample(val root: Int = 60, val tune: Int = 0, val gain: Int = 0, val loop: Loop? = null)
    private data class Wave(val name: String, val data: Chunk, val format: Int, val bits: Int, val rate: Int, val sample: Sample?) {
        val frames get() = (data.end - data.start).toInt() / (bits / 8)
    }
    private data class Connection(val source: Int, val control: Int, val destination: Int, val transform: Int, val scale: Int)
    private data class Region(val keys: Int, val velocities: Int, val group: Int, val wave: Int, val sample: Sample?, val art: List<Connection>)
    private data class Instrument(val name: String, val bank: Int, val program: Int, val regions: List<Region>, val art: List<Connection>)
    private data class Bank(val name: String, val waves: List<Wave>, val instruments: List<Instrument>)

    private class Reader(val file: RandomAccessFile, val checkpoint: () -> Unit) {
        var chunksRead = 0
        fun children(start: Long, end: Long): List<Chunk> {
            val result = mutableListOf<Chunk>()
            var position = start
            while (position < end) {
                checkpoint()
                require(++chunksRead <= 200000 && end - position >= 8) { "Invalid DLS chunk table" }
                file.seek(position)
                val id = file.tag()
                val size = file.u32()
                val data = position + 8
                require(size <= end - data) { "Truncated DLS $id chunk" }
                val type = if (id == "LIST") { require(size >= 4); file.tag() } else ""
                result += Chunk(id, data, data + size, type)
                position = data + size + (size and 1)
                require(position <= end) { "Missing DLS chunk padding" }
            }
            return result
        }
        fun children(chunk: Chunk) = children(chunk.start + 4, chunk.end)
        fun List<Chunk>.required(id: String, size: Int): Chunk = singleOrNull { it.id == id }
            ?.also { require(it.end - it.start >= size) { "Short DLS $id" }; file.seek(it.start) }
            ?: error("Missing or duplicate DLS $id")
        fun name(chunks: List<Chunk>, fallback: String): String {
            val info = chunks.firstOrNull { it.type == "INFO" } ?: return fallback
            val name = children(info).firstOrNull { it.id == "INAM" } ?: return fallback
            file.seek(name.start)
            return ByteArray(minOf(255L, name.end - name.start).toInt()).also(file::readFully)
                .toString(Charsets.ISO_8859_1).substringBefore('\u0000').trim().ifBlank { fallback }
        }
        fun sample(chunks: List<Chunk>): Sample? {
            val chunk = chunks.firstOrNull { it.id == "wsmp" } ?: return null
            require(chunk.end - chunk.start >= 20) { "Short DLS wsmp" }
            file.seek(chunk.start)
            val size = file.u32()
            require(size in 20..(chunk.end - chunk.start)) { "Invalid DLS wsmp size" }
            val root = file.u16()
            val tune = file.u16().toShort().toInt()
            val gain = file.u32().toInt()
            file.u32() // storage options
            val count = file.u32()
            require(root <= 127 && count <= 1) { "Unsupported DLS root key or multiple sample loops" }
            val loop = if (count == 0L) null else {
                file.seek(chunk.start + size)
                require(chunk.end - file.filePointer >= 16) { "Short DLS loop" }
                val loopSize = file.u32()
                require(loopSize in 16..(chunk.end - chunk.start - size))
                val type = file.u32().toInt()
                val start = file.u32()
                val length = file.u32()
                require(type in 0..1 && start <= Int.MAX_VALUE && length in 1..Int.MAX_VALUE.toLong()) { "Invalid DLS loop" }
                Loop(type, start.toInt(), length.toInt())
            }
            return Sample(root, tune, gain, loop)
        }
        fun articulations(chunks: List<Chunk>): List<Connection> {
            // DLS2 articulations supersede DLS1 when both are present.
            val lists = chunks.filter { it.type == "lar2" }.ifEmpty { chunks.filter { it.type == "lart" } }
            return lists.flatMap { list -> children(list).filter { it.id == "art1" || it.id == "art2" }.flatMap { chunk ->
                require(chunk.end - chunk.start >= 8)
                file.seek(chunk.start)
                val size = file.u32()
                val count = file.u32()
                require(size >= 8 && size <= chunk.end - chunk.start && count <= 65535 && count * 12 <= chunk.end - chunk.start - size) { "Invalid DLS articulation" }
                file.seek(chunk.start + size)
                List(count.toInt()) { Connection(file.u16(), file.u16(), file.u16(), file.u16(), file.u32().toInt()) }
            } }
        }
        fun read(): Bank {
            require(file.length() >= 12 && file.tag() == "RIFF") { "Not a RIFF DLS file" }
            val size = file.u32()
            require(size >= 4 && size + 8 <= file.length() && file.tag() == "DLS ") { "Invalid DLS header" }
            val top = children(12, size + 8)
            val pool = top.singleOrNull { it.type == "wvpl" } ?: error("Missing DLS wave pool")
            val waveChunks = children(pool).filter { it.type == "wave" }
            require(waveChunks.size in 1..65534) { "Invalid DLS wave count" }
            // A cue is a byte offset, NOT necessarily the wave's ordinal in the pool.
            val offsets = waveChunks.mapIndexed { index, chunk -> (chunk.start - 8 - (pool.start + 4)) to index }.toMap()
            val ptbl = top.required("ptbl", 8)
            val headerSize = file.u32()
            val count = file.u32()
            require(headerSize >= 8 && headerSize <= ptbl.end - ptbl.start && count <= 65535 && count * 4 <= ptbl.end - ptbl.start - headerSize) { "Invalid DLS pool table" }
            file.seek(ptbl.start + headerSize)
            val cues = List(count.toInt()) { offsets[file.u32()] ?: error("DLS cue does not reference a wave") }
            val waves = waveChunks.mapIndexed { index, chunk ->
                val parts = children(chunk)
                parts.required("fmt ", 16)
                val format = file.u16()
                val channels = file.u16()
                val rate = file.u32()
                file.u32()
                val align = file.u16()
                val bits = file.u16()
                require(channels == 1 && ((format == 1 && bits in listOf(8, 16)) || (format == 6 && bits == 8))) {
                    "Unsupported DLS wave: format=$format, channels=$channels, bits=$bits (PCM8/16 or A-law mono required)"
                }
                require(rate in 400..192000 && align == bits / 8) { "Invalid DLS sample format" }
                val data = parts.required("data", 1)
                require(data.end - data.start <= Int.MAX_VALUE && (data.end - data.start) % align == 0L) { "Invalid DLS sample data" }
                Wave(name(parts, "Wave $index"), data, format, bits, rate.toInt(), sample(parts))
            }
            val instruments = top.singleOrNull { it.type == "lins" }?.let(::children)
                ?.filter { it.type == "ins " }?.mapIndexed { index, chunk ->
                    val parts = children(chunk)
                    parts.required("insh", 12)
                    val regionCount = file.u32()
                    val bank = file.u32().toInt()
                    val program = file.u32()
                    require(program <= 127) { "Invalid DLS program" }
                    val regions = parts.singleOrNull { it.type == "lrgn" }?.let(::children)
                        ?.filter { it.type == "rgn " || it.type == "rgn2" }?.map { region ->
                            val fields = children(region)
                            fields.required("rgnh", 12)
                            fun range(): Int {
                                val low = file.u16(); val high = file.u16()
                                require(low in 0..127 && high in low..127) { "Invalid DLS region range" }
                                return low or (high shl 8)
                            }
                            val keys = range(); val velocities = range()
                            file.u16()
                            val group = file.u16()
                            require(group <= 127) { "Unsupported DLS key group" }
                            fields.required("wlnk", 12)
                            val flags = file.u16(); file.u16()
                            val channel = file.u32()
                            require(flags and 2 == 0 && channel <= 1) { "Unsupported DLS multichannel wave link" }
                            val cue = file.u32()
                            require(cue < cues.size) { "Invalid DLS wave link" }
                            Region(keys, velocities, group, cues[cue.toInt()], sample(fields), articulations(fields))
                        } ?: error("Missing DLS regions")
                    require(regions.isNotEmpty() && regionCount == regions.size.toLong()) { "Invalid DLS region count" }
                    val msb = (bank ushr 8) and 127
                    Instrument(name(parts, "Instrument $index"), if (bank < 0) 128 else if (msb != 0) msb else bank and 127,
                        program.toInt(), regions, articulations(parts))
                } ?: error("Missing DLS instruments")
            require(instruments.size in 1..65534) { "Invalid DLS instrument count" }
            return Bank(name(top, "Converted DLS"), waves, instruments)
        }
    }

    private data class Mod(val source: Int, val destination: Int, val amount: Int, val control: Int = 0)
    private data class Zone(val generators: LinkedHashMap<Int, Int> = linkedMapOf(), val modulators: MutableList<Mod> = mutableListOf())
    private val destinations = mapOf(1 to 48, 3 to 52, 4 to 17, 5 to 58, 0x80 to 15, 0x81 to 16,
        0x104 to 22, 0x105 to 21, 0x114 to 24, 0x115 to 23,
        0x206 to 34, 0x207 to 36, 0x209 to 38, 0x20a to 37, 0x20b to 33, 0x20c to 35,
        0x30a to 26, 0x30b to 28, 0x30d to 30, 0x30e to 29, 0x30f to 25, 0x310 to 27,
        0x500 to 8, 0x501 to 9)
    private fun special(source: Int, destination: Int): Int? = when (source to destination) {
        1 to 1 -> 13; 1 to 3 -> 5; 1 to 0x500 -> 10
        5 to 3 -> 7; 5 to 0x500 -> 11; 9 to 3 -> 6
        else -> null
    }
    private fun source(value: Int): Int? = when (value) {
        0, 2, 3 -> value; 6 -> 14; 7 -> 10; 8 -> 13; 0x100 -> 16
        in 0x80..0xff -> value
        else -> null
    }
    private fun articulation(connections: List<Connection>): Zone {
        val zone = Zone()
        val corrections = mutableListOf<Pair<Int, Int>>()
        connections.forEach { c ->
            var amount = c.scale shr 16
            val special = special(c.source, c.destination)
            val dest = special ?: destinations[c.destination] ?: return@forEach
            if (c.control == 0 && c.transform == 0 && (c.source == 0 || special != null)) {
                if (c.source == 0 && dest == 52) {
                    zone.generators[51] = amount / 100
                    zone.generators[52] = amount % 100
                } else {
                    if (c.source == 0 && dest == 48) amount = (-c.scale / 65536.0 / 0.4).toInt().coerceIn(0, 1440)
                    if (dest == 37 || dest == 29) amount = (1000 - amount).coerceIn(0, 1000)
                    zone.generators[dest] = amount
                }
            } else if (c.source == 3 && c.control == 0 && c.transform == 0 && c.destination in listOf(3, 0x20c, 0x207, 0x310, 0x30b)) {
                if (c.destination == 3) zone.generators[56] = amount / 128 else {
                    val keyDest = mapOf(0x20c to 39, 0x207 to 40, 0x310 to 31, 0x30b to 32).getValue(c.destination)
                    zone.generators[keyDest] = amount / -128
                    if (amount / -128 <= 120) corrections += dest to kotlin.math.round(amount * 60.0 / 128).toInt()
                }
            } else {
                fun input(value: Int, curve: Int, bipolar: Boolean, reverse: Boolean): Int? {
                    val input = source(value) ?: return null
                    if (input == 0) return 0
                    if (curve !in 0..3) return null
                    return input or (curve shl 10) or (if (bipolar) 512 else 0) or (if (reverse) 256 else 0)
                }
                val curve = ((c.transform ushr 10) and 15).let { if (it == 0) c.transform and 15 else it }
                val src = if (special != null) 0 else input(c.source, curve, c.transform and 0x4000 != 0,
                    c.transform and 0x8000 != 0) ?: return@forEach
                val control = input(c.control, (c.transform ushr 4) and 15, c.transform and 0x100 != 0,
                    c.transform and 0x200 != 0) ?: return@forEach
                if (dest == 48) amount = -amount
                zone.modulators += if (special != null) Mod(control, dest, amount) else Mod(src, dest, amount, control)
            }
        }
        corrections.forEach { (dest, amount) -> zone.generators[dest] = (zone.generators[dest] ?: -12000) + amount }
        return zone
    }

    private fun writeBank(input: RandomAccessFile, out: RandomAccessFile, bank: Bank, checkpoint: () -> Unit) {
        val starts = mutableListOf<Int>()
        val zones = mutableListOf<Zone>()
        val instrumentBags = mutableListOf<Int>()
        bank.instruments.forEach { instrument ->
            checkpoint()
            instrumentBags += zones.size
            val global = articulation(instrument.art)
            if (global.modulators.none { it.destination == 16 }) global.modulators += Mod(0xdb, 16, 1000)
            if (global.modulators.none { it.destination == 15 }) global.modulators += Mod(0xdd, 15, 1000)
            zones += global
            instrument.regions.forEach { region ->
                val wave = bank.waves[region.wave]
                val sample = region.sample ?: wave.sample ?: Sample()
                val zone = Zone(linkedMapOf(43 to region.keys, 44 to region.velocities))
                val art = articulation(region.art)
                zone.generators.putAll(art.generators); zone.modulators.addAll(art.modulators)
                if (region.group != 0) zone.generators[57] = region.group
                zone.generators[58] = sample.root
                zone.generators[51] = (zone.generators[51] ?: global.generators[51] ?: 0) + sample.tune / 100
                zone.generators[52] = (zone.generators[52] ?: global.generators[52] ?: 0) + sample.tune % 100
                zone.generators[48] = ((zone.generators[48] ?: global.generators[48] ?: 0) - sample.gain / 65536.0 / 0.4).toInt().coerceIn(0, 1440)
                sample.loop?.let { loop ->
                    require(loop.start.toLong() + loop.length <= wave.frames) { "DLS loop exceeds sample data" }
                    zone.generators[54] = if (loop.type == 0) 1 else 3
                    // Sample headers use [0, frames]; region loops are expressed as offsets.
                    val end = loop.start + loop.length - wave.frames
                    zone.generators[45] = loop.start / 32768; zone.generators[2] = loop.start % 32768
                    zone.generators[50] = end / 32768; zone.generators[3] = end % 32768
                }
                zone.generators[53] = region.wave // sampleID must be last
                zones += zone
            }
        }
        require(zones.size < 65535 && zones.sumOf { it.generators.size.toLong() } < 65535 && zones.sumOf { it.modulators.size.toLong() } < 65535) { "DLS exceeds SF2 zone/generator limits" }
        out.chunk("RIFF", "sfbk") {
            chunk("LIST", "INFO") {
                chunk("ifil") { u16(2); u16(4) }
                chunk("isng") { write("EMU8000\u0000".toByteArray()) }
                chunk("INAM") { write(bank.name.toByteArray(Charsets.ISO_8859_1)); write(0) }
            }
            chunk("LIST", "sdta") {
                chunk("smpl") {
                    var frames = 0L
                    val buffer = ByteArray(65536)
                    bank.waves.forEach { wave ->
                        require(frames + wave.frames + 46 <= Int.MAX_VALUE) { "DLS exceeds SF2 sample size limit" }
                        starts += frames.toInt()
                        input.seek(wave.data.start)
                        var remaining = wave.data.end - wave.data.start
                        while (remaining > 0) {
                            checkpoint()
                            val count = minOf(remaining, buffer.size.toLong()).toInt()
                            input.readFully(buffer, 0, count)
                            if (wave.bits == 16) write(buffer, 0, count) else {
                                val pcm = ByteArray(count * 2)
                                repeat(count) { index ->
                                    val value = buffer[index].toInt() and 255
                                    val sample = if (wave.format == 6) decodeALaw(value) else (value - 128) shl 8
                                    pcm[index * 2] = sample.toByte(); pcm[index * 2 + 1] = (sample shr 8).toByte()
                                }
                                write(pcm)
                            }
                            remaining -= count
                        }
                        write(ByteArray(92)) // SF2 requires 46 zero guard samples after EACH wave.
                        frames += wave.frames + 46L
                    }
                }
            }
            chunk("LIST", "pdta") {
                chunk("phdr") {
                    bank.instruments.forEachIndexed { index, instrument ->
                        name(instrument.name); u16(instrument.program); u16(instrument.bank); u16(index); write(ByteArray(12))
                    }
                    name("EOP"); u16(0); u16(0); u16(bank.instruments.size); write(ByteArray(12))
                }
                chunk("pbag") { repeat(bank.instruments.size + 1) { u16(it); u16(0) } }
                chunk("pmod") { write(ByteArray(10)) }
                chunk("pgen") { bank.instruments.indices.forEach { u16(41); u16(it) }; write(ByteArray(4)) }
                chunk("inst") {
                    bank.instruments.forEachIndexed { index, instrument -> name(instrument.name); u16(instrumentBags[index]) }
                    name("EOI"); u16(zones.size)
                }
                chunk("ibag") {
                    var gen = 0; var mod = 0
                    zones.forEach { u16(gen); u16(mod); gen += it.generators.size; mod += it.modulators.size }
                    u16(gen); u16(mod)
                }
                chunk("imod") {
                    zones.flatMap { it.modulators }.forEach { u16(it.source); u16(it.destination); u16(it.amount); u16(it.control); u16(0) }
                    write(ByteArray(10))
                }
                chunk("igen") {
                    zones.forEach { zone -> zone.generators.forEach { (op, amount) ->
                        u16(op); u16(if (op in listOf(43, 44, 53)) amount else amount.coerceIn(-32768, 32767))
                    } }
                    write(ByteArray(4))
                }
                chunk("shdr") {
                    bank.waves.forEachIndexed { index, wave ->
                        name(wave.name); u32(starts[index].toLong()); u32(starts[index].toLong() + wave.frames)
                        u32(starts[index].toLong()); u32(starts[index].toLong() + wave.frames)
                        u32(wave.rate.toLong()); write(60); write(0); u16(0); u16(1)
                    }
                    name("EOS"); write(ByteArray(26))
                }
            }
        }
        checkpoint()
    }
    private fun decodeALaw(value: Int): Int {
        val a = value xor 0x55
        val segment = (a and 0x70) shr 4
        var sample = (a and 15) shl 4
        sample += if (segment == 0) 8 else 0x108
        if (segment > 1) sample = sample shl (segment - 1)
        return if (a and 0x80 != 0) sample else -sample
    }
    private fun RandomAccessFile.tag() = ByteArray(4).also(::readFully).toString(Charsets.US_ASCII)
    private fun RandomAccessFile.u16() = readUnsignedByte() or (readUnsignedByte() shl 8)
    private fun RandomAccessFile.u32() = u16().toLong() or (u16().toLong() shl 16)
    private fun RandomAccessFile.u16(value: Int) { write(value and 255); write((value ushr 8) and 255) }
    private fun RandomAccessFile.u32(value: Long) { require(value in 0..0xffffffffL); u16(value.toInt()); u16((value ushr 16).toInt()) }
    private fun RandomAccessFile.name(value: String) = write(value.toByteArray(Charsets.ISO_8859_1).copyOf(20))
    private fun RandomAccessFile.chunk(id: String, type: String = "", body: RandomAccessFile.() -> Unit) {
        write(id.toByteArray(Charsets.US_ASCII)); val sizeAt = filePointer; u32(0)
        write(type.toByteArray(Charsets.US_ASCII)); body()
        val end = filePointer
        seek(sizeAt); u32(end - sizeAt - 4); seek(end)
        if ((end - sizeAt - 4) and 1L != 0L) write(0)
    }
}

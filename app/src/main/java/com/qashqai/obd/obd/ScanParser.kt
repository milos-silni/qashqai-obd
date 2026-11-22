package com.qashqai.obd.obd

import java.util.Locale

/** One complete diagnostic message from an ECU (ISO-TP reassembled), e.g. 61 01 ... */
class EcuMessage(val header: String?, val bytes: IntArray) {
    val service get() = bytes.firstOrNull()
    /** Negative response 7F <service> <code>. */
    val isNegative get() = bytes.size >= 3 && bytes[0] == 0x7F
    val nrc get() = if (isNegative) bytes[2] else null
    fun isPositiveFor(service: Int) = bytes.isNotEmpty() && bytes[0] == service + 0x40
    override fun toString() = (header?.let { "$it: " } ?: "") + ScanParser.hex(bytes)
}

/** One raw CAN frame seen while monitoring the bus. */
class CanFrame(val tMs: Long, val id: String, val data: IntArray)

data class ByteStats(val distinct: Int, val min: Int, val max: Int, val changes: Int, val ups: Int, val stepOne: Int)

data class IdStats(val id: String, val count: Int, val hz: Double, val dlc: Int, val last: IntArray, val bytes: List<ByteStats>) {
    /** Byte positions that only move upwards (apart from wrap-around) but not on every frame – the shape of a fuel/distance counter. */
    val counterLike: List<Int> get() = bytes.indices.filter { i ->
        val b = bytes[i]
        b.changes >= 3 && b.ups >= b.changes * 0.85 && !isAliveCounter(i)
    }
    /** Changes on (almost) every frame by exactly 1 – a message sequence counter, not data. */
    fun isAliveCounter(i: Int) = bytes[i].changes >= (count - 1) * 0.9 && bytes[i].stepOne >= bytes[i].changes * 0.9
}

/** Pure parsing for the ECU scan and CAN capture tools (spaces ON in adapter replies). */
object ScanParser {

    fun hex(b: IntArray) = b.joinToString(" ") { String.format(Locale.US, "%02X", it) }

    /** Printable ASCII of the bytes, other bytes as '.'; null when there is hardly any text in it. */
    fun ascii(b: IntArray): String? {
        val printable = b.count { it in 0x20..0x7E }
        if (printable < 4 || printable < b.size / 2) return null
        return b.joinToString("") { if (it in 0x20..0x7E) it.toChar().toString() else "." }
    }

    private fun tokens(line: String) = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    private fun isHexByte(t: String) = t.length == 2 && t.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

    /** "7E8 06 41 00 BE 3E B8 11" -> header + bytes. [headerTokens] = 1 for 11-bit CAN, 4 for 29-bit. */
    fun frame(line: String, headerTokens: Int): Pair<String, IntArray>? {
        val t = tokens(line)
        if (t.size <= headerTokens) return null
        val header = t.take(headerTokens)
        val okHeader = if (headerTokens == 1) header[0].length == 3 && header[0].all { it.isLetterOrDigit() } &&
            header[0].uppercase(Locale.US).all { it in '0'..'9' || it in 'A'..'F' }
        else header.all(::isHexByte)
        if (!okHeader) return null
        val data = t.drop(headerTokens)
        if (!data.all(::isHexByte)) return null
        return header.joinToString("").uppercase(Locale.US) to IntArray(data.size) { data[it].toInt(16) }
    }

    /**
     * CAN replies with headers ON: reassemble ISO-TP (single / first / consecutive frames) per sender.
     * Lines that are not frames (NO DATA, errors) are skipped.
     */
    fun canMessages(lines: List<String>, headerTokens: Int): List<EcuMessage> {
        val out = mutableListOf<EcuMessage>()
        val pending = HashMap<String, Pair<Int, MutableList<Int>>>()   // header -> (total length, collected)
        for (line in lines) {
            val (header, b) = frame(line, headerTokens) ?: continue
            if (b.isEmpty()) continue
            val pci = b[0]
            when (pci shr 4) {
                0x0 -> {
                    val len = pci and 0x0F
                    if (len in 1 until b.size) out += EcuMessage(header, b.copyOfRange(1, 1 + len))
                    else out += EcuMessage(header, b.copyOfRange(1, b.size))
                }
                0x1 -> if (b.size >= 2) {
                    val total = ((pci and 0x0F) shl 8) or b[1]
                    pending[header] = total to b.drop(2).toMutableList()
                }
                0x2 -> pending[header]?.let { (total, got) ->
                    got += b.drop(1)
                    if (got.size >= total) {
                        out += EcuMessage(header, got.take(total).toIntArray())
                        pending.remove(header)
                    }
                }
                0x3 -> {}   // flow control from the other side
                else -> out += EcuMessage(header, b)
            }
        }
        // a message cut short is still worth seeing in the report
        pending.forEach { (h, p) -> out += EcuMessage(h, p.second.toIntArray()) }
        return out
    }

    /** Headers OFF (non-CAN): every hex line is one message. */
    fun plainMessages(lines: List<String>): List<EcuMessage> = lines.mapNotNull { l ->
        val t = tokens(l)
        if (t.isNotEmpty() && t.all(::isHexByte)) EcuMessage(null, IntArray(t.size) { t[it].toInt(16) }) else null
    }

    /** Text after "49 <pid>" in mode 09 replies (VIN, ECU name, calibration id). */
    fun mode09Text(messages: List<EcuMessage>, pid: Int): String? {
        val bytes = messages.firstOrNull { it.isPositiveFor(0x09) && it.bytes.size > 2 && it.bytes[1] == pid }?.bytes ?: return null
        val text = bytes.drop(2).filter { it in 0x20..0x7E }.joinToString("") { it.toChar().toString() }.trim()
        return text.ifEmpty { null }
    }

    fun nrcName(code: Int): String = when (code) {
        0x10 -> "general reject"
        0x11 -> "service not supported"
        0x12 -> "not supported"
        0x13 -> "bad length"
        0x22 -> "conditions not correct"
        0x31 -> "out of range"
        0x33 -> "security access denied"
        0x78 -> "busy, answer pending"
        0x7E, 0x7F -> "not supported in this session"
        0x80 -> "not supported in this session"
        else -> String.format(Locale.US, "code %02X", code)
    }

    fun summarize(frames: List<CanFrame>, durationMs: Long): List<IdStats> {
        val seconds = (durationMs / 1000.0).coerceAtLeast(0.001)
        return frames.groupBy { it.id }.map { (id, fs) ->
            val dlc = fs.maxOf { it.data.size }
            val stats = (0 until dlc).map { i ->
                val seq = fs.mapNotNull { it.data.getOrNull(i) }
                var changes = 0; var ups = 0; var stepOne = 0
                for (k in 1 until seq.size) {
                    val d = seq[k] - seq[k - 1]
                    if (d == 0) continue
                    changes++
                    // small rise, or a wrap 0xFF -> 0x0? counts as going up
                    if (d in 1..127 || d <= -128) ups++
                    if (d == 1 || d == -15 || d == -255) stepOne++   // +1, or a 4/8-bit counter wrapping
                }
                ByteStats(seq.toSet().size, seq.minOrNull() ?: 0, seq.maxOrNull() ?: 0, changes, ups, stepOne)
            }
            IdStats(id, fs.size, fs.size / seconds, dlc, fs.last().data, stats)
        }.sortedBy { it.id.padStart(8, '0') }
    }
}

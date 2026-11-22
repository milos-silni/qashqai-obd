package com.qashqai.obd.obd

import java.util.Locale

/** Pure functions for turning ELM327 text replies into data. No Android dependencies, unit-testable. */
object ElmParser {

    sealed class Problem {
        object NoData : Problem()
        object Unsupported : Problem()
        data class Bus(val message: String) : Problem()
    }

    private val busErrors = listOf(
        "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS BUSY", "STOPPED",
        "BUFFER FULL", "DATA ERROR", "RX ERROR", "FB ERROR", "LV RESET", "ACT ALERT",
    )

    /** Split raw reply into meaningful lines, dropping prompt, echo and "SEARCHING..." noise. */
    fun cleanLines(raw: String, echo: String? = null): List<String> {
        val echoNorm = echo?.replace(" ", "")?.uppercase(Locale.US)
        return raw.replace("\u0000", "").replace(">", "")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { echoNorm != null && it.replace(" ", "").uppercase(Locale.US) == echoNorm }
            .filterNot { it.startsWith("SEARCHING", ignoreCase = true) }
            .filterNot { it.startsWith("BUS INIT", ignoreCase = true) && it.endsWith("OK", ignoreCase = true) }
    }

    fun problem(lines: List<String>): Problem? {
        if (lines.isEmpty()) return Problem.NoData
        val joined = lines.joinToString(" ").uppercase(Locale.US)
        busErrors.firstOrNull { joined.contains(it) }?.let { return Problem.Bus(it) }
        // "ERROR" alone (clones), but not inside a hex line
        if (lines.any { it.uppercase(Locale.US).startsWith("ERR") }) return Problem.Bus("ERROR")
        if (joined.contains("NO DATA")) return Problem.NoData
        if (lines.all { it == "?" }) return Problem.Unsupported
        return null
    }

    /** Hex line -> bytes. Strips CAN multi-frame index prefix "0:". Returns null for non-hex/odd lines. */
    fun hexBytes(line: String): IntArray? {
        var s = line.replace(" ", "").uppercase(Locale.US)
        val colon = s.indexOf(':')
        if (colon in 1..2) s = s.substring(colon + 1)
        if (s.isEmpty() || s.length % 2 != 0) return null
        if (!s.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return IntArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    /** Data bytes following "41 <pid>" for every responding ECU. */
    fun parseMode01All(lines: List<String>, pid: Int): List<IntArray> {
        val out = mutableListOf<IntArray>()
        for (line in lines) {
            val b = hexBytes(line) ?: continue
            for (i in 0 until b.size - 1) {
                if (b[i] == 0x41 && b[i + 1] == pid) {
                    out += b.copyOfRange(i + 2, b.size)
                    break
                }
            }
        }
        return out
    }

    fun parseMode01(lines: List<String>, pid: Int): IntArray? = parseMode01All(lines, pid).firstOrNull()

    /** Bitmap reply to 01 00 / 01 20 / ... -> set of supported PID numbers. */
    fun decodeSupported(base: Int, data: IntArray): Set<Int> {
        val out = mutableSetOf<Int>()
        for (byteIdx in 0 until minOf(4, data.size)) {
            for (bit in 0 until 8) {
                if (data[byteIdx] and (0x80 shr bit) != 0) out += base + byteIdx * 8 + bit + 1
            }
        }
        return out
    }

    /** ATDPN reply like "A6" or "6" -> true for ISO 15765 CAN protocols (6..9) and SAE J1939 / user CAN (A..C). */
    fun isCanProtocol(dpn: String): Boolean {
        val c = dpn.trim().uppercase(Locale.US).removePrefix("A").firstOrNull() ?: return false
        return c in '6'..'9' || c in 'A'..'C'
    }

    /** ATDPN "7"/"9" (or "A7"/"A9") = ISO 15765 with 29-bit identifiers. */
    fun isCan29Bit(dpn: String): Boolean =
        dpn.trim().uppercase(Locale.US).removePrefix("A").firstOrNull().let { it == '7' || it == '9' }

    fun decodeDtc(a: Int, b: Int): String {
        val type = "PCBU"[(a shr 6) and 3]
        return String.format(Locale.US, "%c%d%X%02X", type, (a shr 4) and 3, a and 0xF, b)
    }

    /** Mode 03 reply -> list of trouble codes. */
    fun parseDtcs(lines: List<String>, isCan: Boolean): List<String> {
        val hexLines = lines.filter { hexBytes(it) != null }
        val multiFrame = lines.any { Regex("^[0-9A-Fa-f]{1,2}:").containsMatchIn(it.trim()) }
        val messages: List<IntArray> = if (multiFrame) {
            listOf(lines.filter { it.contains(':') }.flatMap { hexBytes(it)?.toList() ?: emptyList() }.toIntArray())
        } else {
            hexLines.mapNotNull { hexBytes(it) }
        }
        val codes = linkedSetOf<String>()
        for (msg in messages) {
            val start = msg.indexOf(0x43)
            if (start < 0) continue
            if (isCan) {
                if (start + 1 >= msg.size) continue
                val count = msg[start + 1]
                var i = start + 2
                repeat(count) {
                    if (i + 1 < msg.size) codes += decodeDtc(msg[i], msg[i + 1])
                    i += 2
                }
            } else {
                var i = start + 1
                while (i + 1 < msg.size) {
                    if (msg[i] != 0 || msg[i + 1] != 0) codes += decodeDtc(msg[i], msg[i + 1])
                    i += 2
                }
            }
        }
        codes.remove("P0000")
        return codes.toList()
    }

    /** "12.6V" -> 12.6 */
    fun parseVoltage(lines: List<String>): Double? =
        lines.firstNotNullOfOrNull { Regex("""(\d+(\.\d+)?)\s*V""", RegexOption.IGNORE_CASE).find(it) }
            ?.groupValues?.get(1)?.toDoubleOrNull()
}

package com.qashqai.obd.scan

import com.qashqai.obd.obd.EcuMessage
import com.qashqai.obd.obd.Elm327
import com.qashqai.obd.obd.ElmParser
import com.qashqai.obd.obd.ElmTimeoutException
import com.qashqai.obd.obd.ScanParser
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanResult(val report: String, val summary: List<String>)

/**
 * Finds out what the car will tell us: every standard PID, vehicle info, and the engine ECU's
 * manufacturer (Renault KWP2000) data blocks – where the injected fuel quantity lives.
 * Only read services are used: 01, 09, 10 (diagnostic session), 21 and 22.
 */
class EcuScanner(
    private val e: Elm327,
    private val supported: Set<Int>,
    private val progress: (String, Float?) -> Unit,
) {
    private val out = StringBuilder()
    private val summary = mutableListOf<String>()
    private val headerTokens get() = if (e.canExtended) 4 else 1

    suspend fun run(): ScanResult {
        out.appendLine("Qashqai OBD – ECU scan, ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        out.appendLine("Read-only requests. Each request is followed by the adapter's raw reply; '=' lines are decoded messages.")
        var physical = false
        var session = false
        try {
            progress("Adapter info", 0f)
            section("Adapter")
            for (cmd in listOf("ATI", "AT@1", "ATDP", "ATDPN", "ATRV")) {
                out.appendLine("$cmd: " + runCatching { e.at(cmd) }.getOrElse { "error: ${it.message}" })
            }
            e.at("ATS1")                 // spaces between bytes
            if (e.isCan) e.at("ATH1")    // show who answers (7E8 = engine)

            section("Mode 01 – standard live data (every ECU that answers)")
            val pids = (supported + 0x00).sorted()
            val senders = sortedSetOf<String>()
            pids.forEachIndexed { i, pid ->
                progress("Standard data ${i + 1}/${pids.size}", 0.02f + 0.15f * i / pids.size)
                request(String.format(Locale.US, "01%02X", pid)).forEach { m -> m.header?.let(senders::add) }
            }
            summary += "Standard PIDs: ${supported.size} supported" + if (senders.isNotEmpty()) ", answered by ${senders.joinToString()}" else ""

            section("Mode 09 – vehicle information")
            val info = mutableMapOf<Int, String>()
            for ((i, pid) in listOf(0x00, 0x02, 0x04, 0x06, 0x0A).withIndex()) {
                progress("Vehicle info", 0.17f + 0.03f * i / 5)
                ScanParser.mode09Text(request(String.format(Locale.US, "09%02X", pid), 4000), pid)?.let { info[pid] = it }
            }
            info[0x02]?.let { summary += "VIN: $it" }
            info[0x0A]?.let { summary += "ECU name: $it" }
            info[0x04]?.let { summary += "Calibration: $it" }

            if (!e.isCan) {
                note("Manufacturer data scan needs a CAN connection – skipped.")
                summary += "Manufacturer data: skipped (car is not on CAN)"
                return finish()
            }

            section("Engine ECU – manufacturer data (physical address)")
            val (req, resp) = if (e.canExtended) "18DA10F1" to "18DAF110" else "7E0" to "7E8"
            e.at("ATSH$req")
            runCatching { e.at("ATCRA$resp") }
            physical = true
            out.appendLine("Requests to $req, replies from $resp")

            progress("Opening diagnostic session", 0.2f)
            val s = request("10C0")
            session = s.any { it.isPositiveFor(0x10) }
            summary += "Engine diagnostic session (10 C0): " + when {
                session -> "accepted"
                s.any { it.isNegative } -> "refused (${ScanParser.nrcName(s.first { it.isNegative }.nrc!!)})"
                else -> "no answer"
            }

            val kwp = scanService(0x21, (0x01..0xFE).map { String.format(Locale.US, "21%02X", it) }, 0.22f, 0.9f)
            summary += if (kwp.isEmpty()) "Manufacturer blocks (21 xx): none answered"
            else "Manufacturer blocks (21 xx): ${kwp.size} found – ${kwp.joinToString(" ")}"

            val uds = scanService(0x22, (0xF180..0xF19F).map { String.format(Locale.US, "22%04X", it) }, 0.9f, 0.98f)
            if (uds.isNotEmpty()) summary += "Identification (22 F1xx): ${uds.size} found"
            return finish()
        } finally {
            withContext(NonCancellable) {
                if (session) runCatching { e.raw("1081", 1500) }      // back to the default session
                if (physical) {
                    runCatching { e.at("ATCRA") }
                    runCatching { e.at("ATAR") }
                    runCatching { e.at(if (e.canExtended) "ATSH18DB33F1" else "ATSH7DF") }
                }
                runCatching { e.at("ATH0") }
                runCatching { e.at("ATS0") }
            }
        }
    }

    private fun finish(): ScanResult {
        progress("Done", 1f)
        val head = StringBuilder()
        head.appendLine(out.lineSequence().first())
        head.appendLine()
        head.appendLine("SUMMARY")
        summary.forEach { head.appendLine("  $it") }
        return ScanResult(head.toString() + out.lineSequence().drop(1).joinToString("\n"), summary.toList())
    }

    /** Requests every id of a service; prints answered ones in full, counts the refusals. Returns the ids that answered. */
    private suspend fun scanService(service: Int, cmds: List<String>, from: Float, to: Float): List<String> {
        val name = String.format(Locale.US, "%02X", service)
        out.appendLine()
        out.appendLine("-- Service $name: ${cmds.first()} … ${cmds.last()} (only answered requests are listed)")
        val found = mutableListOf<String>()
        val refused = sortedMapOf<Int, Int>()
        var silent = 0
        for ((i, cmd) in cmds.withIndex()) {
            progress("Service $name: ${cmd.drop(2)}  (${found.size} found)", from + (to - from) * i / cmds.size)
            val (lines, msgs) = exchange(cmd)
            val positive = msgs.filter { it.isPositiveFor(service) }
            when {
                positive.isNotEmpty() -> { found += cmd.drop(2); print(cmd, lines, positive) }
                msgs.any { it.isNegative } -> msgs.first { it.isNegative }.nrc!!.let { refused[it] = (refused[it] ?: 0) + 1 }
                else -> silent++
            }
            // the ECU doesn't do this service at all: don't spend a minute asking
            if (i == 7 && found.isEmpty() && (refused[0x11] == 8 || silent == 8)) {
                out.appendLine("   Stopped after 8 requests: " + if (silent == 8) "no answer" else "service not supported")
                break
            }
        }
        out.appendLine("   Answered: ${found.size}" +
            refused.entries.joinToString("") { (code, n) -> ", refused ×$n (${ScanParser.nrcName(code)})" } +
            if (silent > 0) ", no answer ×$silent" else "")
        return found
    }

    private suspend fun exchange(cmd: String, timeoutMs: Long = 1500): Pair<List<String>, List<EcuMessage>> {
        val raw = try {
            e.raw(cmd, timeoutMs)
        } catch (t: ElmTimeoutException) {
            return listOf("(no reply within ${timeoutMs} ms)") to emptyList()
        }
        val lines = ElmParser.cleanLines(raw, cmd)
        val msgs = if (e.isCan) ScanParser.canMessages(lines, headerTokens) else ScanParser.plainMessages(lines)
        return lines to msgs
    }

    private suspend fun request(cmd: String, timeoutMs: Long = 1500): List<EcuMessage> {
        val (lines, msgs) = exchange(cmd, timeoutMs)
        print(cmd, lines, msgs.filter { !it.isNegative })
        return msgs
    }

    private fun print(cmd: String, lines: List<String>, decoded: List<EcuMessage>) {
        out.appendLine("> $cmd")
        lines.forEach { out.appendLine("    $it") }
        if (e.isCan) decoded.forEach { m ->
            out.appendLine("    = $m" + (ScanParser.ascii(m.bytes)?.let { "   \"$it\"" } ?: ""))
        }
    }

    private fun section(title: String) { out.appendLine(); out.appendLine("== $title") }
    private fun note(text: String) = out.appendLine("   $text")
}

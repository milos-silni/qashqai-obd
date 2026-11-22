package com.qashqai.obd.scan

import com.qashqai.obd.obd.CanFrame
import com.qashqai.obd.obd.Elm327
import com.qashqai.obd.obd.ScanParser
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the car's own CAN traffic (ECU <-> instrument cluster etc.) without sending anything to the ECUs.
 * The cluster's trip computer gets its fuel figure from one of these messages; a byte that only ever
 * counts up while the engine runs is the thing to look for.
 */
class CanCapture(private val e: Elm327, private val progress: (String, Float?) -> Unit) {

    suspend fun run(durationMs: Long, carNow: List<String>): ScanResult {
        if (!e.isCan) throw IOException("Bus capture needs a CAN connection (this car answered on another protocol)")
        val headerTokens = if (e.canExtended) 4 else 1
        val frames = ArrayList<CanFrame>()
        val raw = ArrayList<String>()
        val stops = mutableListOf<String>()
        val started = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - started
        try {
            e.at("ATS1"); e.at("ATH1")
            e.at("ATCAF0")   // show every byte, not just diagnostic payloads
            while (elapsed() < durationMs && stops.size < 30) {
                val reason = e.monitor(
                    durationMs - elapsed(),
                    onTick = { progress("Listening… ${frames.size} frames", (elapsed().toFloat() / durationMs).coerceAtMost(1f)) },
                ) { line ->
                    val t = elapsed()
                    if (raw.size < MAX_LINES) raw += "$t\t$line"
                    ScanParser.frame(line, headerTokens)?.let { (id, data) -> if (frames.size < MAX_LINES) frames += CanFrame(t, id, data) }
                } ?: break
                stops += reason   // e.g. BUFFER FULL: the adapter is slower than the bus, start listening again
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { e.at("ATCAF1") }
                runCatching { e.at("ATH0") }
                runCatching { e.at("ATS0") }
            }
        }
        val duration = minOf(elapsed(), durationMs)
        val stats = ScanParser.summarize(frames, duration)
        progress("Done", 1f)

        val r = StringBuilder()
        r.appendLine("Qashqai OBD – CAN capture, ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        r.appendLine()
        r.appendLine("SUMMARY")
        val summary = mutableListOf<String>()
        summary += String.format(Locale.US, "%.0f s, %d frames, %d message IDs", duration / 1000.0, frames.size, stats.size)
        if (frames.isEmpty()) summary += "No bus traffic seen – the OBD port may not carry the car's internal CAN (gateway)"
        if (stops.isNotEmpty()) summary += "Adapter stopped ${stops.size}× (${stops.distinct().joinToString()}) – some frames missed"
        val counters = stats.filter { it.counterLike.isNotEmpty() }
        if (counters.isNotEmpty()) summary += "Rising counters in: " + counters.joinToString { s -> s.id + " " + s.counterLike.joinToString("") { "B$it" } }
        summary.forEach { r.appendLine("  $it") }
        if (carNow.isNotEmpty()) r.appendLine("  Car at start: " + carNow.joinToString(", "))
        r.appendLine()
        r.appendLine("== Message IDs   (Bn = byte n; '+' only counts up, '~' changes, 'c' sequence counter, '.' constant)")
        for (s in stats) {
            val pattern = s.bytes.indices.joinToString(" ") { i ->
                val b = s.bytes[i]
                val mark = when {
                    b.changes == 0 -> "."
                    s.isAliveCounter(i) -> "c"
                    i in s.counterLike -> "+"
                    else -> "~"
                }
                if (b.changes == 0) "B$i." else "B$i$mark(${b.distinct})"
            }
            r.appendLine(String.format(Locale.US, "%-9s %6.1f Hz  n=%-6d last: %-23s  %s", s.id, s.hz, s.count, ScanParser.hex(s.last), pattern))
        }
        r.appendLine()
        r.appendLine("== Raw frames (ms since start, adapter line)" + if (raw.size >= MAX_LINES) " – first $MAX_LINES only" else "")
        raw.forEach { r.appendLine(it) }
        return ScanResult(r.toString(), summary)
    }

    private companion object { const val MAX_LINES = 200_000 }
}

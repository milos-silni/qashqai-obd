package com.qashqai.obd.obd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.coroutineContext

class ElmTimeoutException(cmd: String) : IOException("Adapter did not answer '$cmd'")
class ElmBusException(message: String) : IOException(message)

/** Talks the ELM327 AT/OBD text protocol over any [ObdTransport]. Thread-safe. */
class Elm327(private val transport: ObdTransport, private val log: (String) -> Unit = {}) {

    private val mutex = Mutex()

    /** Append "1" to mode-01 requests so the adapter returns after the first reply (CAN only, ELM >= 1.3). */
    var responseCountSuffix = false
    var isCan = false
    /** 29-bit CAN identifiers (protocols 7 and 9). */
    var canExtended = false
    /** Log every exchange (on during init, off while polling to keep the log readable). */
    var verbose = true

    suspend fun raw(cmd: String, timeoutMs: Long = 2500): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val input = transport.input
            val buf = ByteArray(256)
            // discard anything stale
            while (input.available() > 0) input.read(buf, 0, minOf(buf.size, input.available()))

            transport.output.write("$cmd\r".toByteArray(Charsets.US_ASCII))
            transport.output.flush()

            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                coroutineContext.ensureActive()
                val avail = input.available()
                if (avail > 0) {
                    val n = input.read(buf, 0, minOf(buf.size, avail))
                    if (n < 0) throw IOException("Adapter connection closed")
                    var done = false
                    for (i in 0 until n) {
                        val c = buf[i].toInt().toChar()
                        if (c == '>') { done = true; break }
                        sb.append(c)
                    }
                    if (done) break
                } else {
                    if (System.currentTimeMillis() > deadline) throw ElmTimeoutException(cmd)
                    delay(5)
                }
            }
            val reply = sb.toString()
            if (verbose) log("> $cmd  < ${reply.replace("\r", " ").trim()}")
            reply
        }
    }

    /** AT command; returns cleaned reply text. */
    suspend fun at(cmd: String, timeoutMs: Long = 2500): String =
        ElmParser.cleanLines(raw(cmd, timeoutMs), cmd).joinToString(" ")

    /** All ECU replies for a mode-01 PID. Empty list = NO DATA / unsupported. Throws [ElmBusException] on bus errors. */
    suspend fun queryMode01All(pid: Int, timeoutMs: Long = 2500, allowSuffix: Boolean = true): List<IntArray> {
        val cmd = String.format(Locale.US, "01%02X", pid) + if (allowSuffix && responseCountSuffix) "1" else ""
        val lines = ElmParser.cleanLines(raw(cmd, timeoutMs), cmd)
        when (val p = ElmParser.problem(lines)) {
            is ElmParser.Problem.Bus -> throw ElmBusException(p.message)
            ElmParser.Problem.NoData, ElmParser.Problem.Unsupported -> return emptyList()
            null -> {}
        }
        return ElmParser.parseMode01All(lines, pid)
    }

    suspend fun queryPid(pid: Int): IntArray? = queryMode01All(pid).firstOrNull()

    suspend fun batteryVoltage(): Double? =
        runCatching { ElmParser.parseVoltage(ElmParser.cleanLines(raw("ATRV"), "ATRV")) }.getOrNull()

    suspend fun readDtcs(): List<String> {
        val lines = ElmParser.cleanLines(raw("03", 6000), "03")
        when (val p = ElmParser.problem(lines)) {
            is ElmParser.Problem.Bus -> throw ElmBusException(p.message)
            ElmParser.Problem.NoData -> return emptyList()
            ElmParser.Problem.Unsupported -> throw IOException("Adapter rejected request")
            null -> {}
        }
        return ElmParser.parseDtcs(lines, isCan)
    }

    suspend fun clearDtcs(): Boolean {
        val lines = ElmParser.cleanLines(raw("04", 6000), "04")
        return lines.any { it.replace(" ", "").startsWith("44") }
    }

    /** Test whether the adapter understands the response-count suffix. */
    suspend fun probeResponseCountSuffix(): Boolean = runCatching {
        val lines = ElmParser.cleanLines(raw("01001"), "01001")
        ElmParser.problem(lines) == null && ElmParser.parseMode01(lines, 0) != null
    }.getOrDefault(false)

    /**
     * Listens to all bus traffic (ATMA) for up to [durationMs], passing every line to [onLine].
     * [onTick] is called a few times a second. Returns null when we stopped it, or the adapter's last
     * line when the adapter stopped by itself (typically "BUFFER FULL" on a busy bus).
     */
    suspend fun monitor(durationMs: Long, onTick: () -> Unit = {}, onLine: (String) -> Unit): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val input = transport.input
            val buf = ByteArray(1024)
            while (input.available() > 0) input.read(buf, 0, minOf(buf.size, input.available()))
            transport.output.write("ATMA\r".toByteArray(Charsets.US_ASCII))
            transport.output.flush()

            val line = StringBuilder()
            var last: String? = null
            var adapterStopped = false
            val deadline = System.currentTimeMillis() + durationMs
            var nextTick = 0L
            try {
                while (!adapterStopped) {
                    coroutineContext.ensureActive()
                    val now = System.currentTimeMillis()
                    if (now >= deadline) break
                    if (now >= nextTick) { onTick(); nextTick = now + 250 }
                    val avail = input.available()
                    if (avail <= 0) { delay(5); continue }
                    val n = input.read(buf, 0, minOf(buf.size, avail))
                    if (n < 0) throw IOException("Adapter connection closed")
                    for (i in 0 until n) {
                        when (val c = buf[i].toInt().toChar()) {
                            '>' -> { adapterStopped = true }
                            '\r', '\n' -> {
                                val s = line.toString().trim()
                                line.setLength(0)
                                if (s.isNotEmpty() && !s.equals("ATMA", ignoreCase = true)) { last = s; onLine(s) }
                            }
                            else -> if (!adapterStopped) line.append(c)
                        }
                    }
                }
            } finally {
                if (!adapterStopped) withContext(NonCancellable) { stopMonitor() }
            }
            if (adapterStopped) (last ?: "stopped") else null
        }
    }

    /** Any character ends ATMA; then wait for the prompt so the next command starts clean. */
    private fun stopMonitor() {
        runCatching {
            transport.output.write("\r".toByteArray(Charsets.US_ASCII))
            transport.output.flush()
            val input = transport.input
            val buf = ByteArray(1024)
            val deadline = System.currentTimeMillis() + 3000
            outer@ while (System.currentTimeMillis() < deadline) {
                val avail = input.available()
                if (avail <= 0) { Thread.sleep(10); continue }
                val n = input.read(buf, 0, minOf(buf.size, avail))
                for (i in 0 until n) if (buf[i].toInt().toChar() == '>') break@outer
            }
            Thread.sleep(50)
            while (input.available() > 0) input.read(buf, 0, minOf(buf.size, input.available()))
        }
    }
}

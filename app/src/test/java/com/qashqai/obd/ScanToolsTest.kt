package com.qashqai.obd

import com.qashqai.obd.obd.Elm327
import com.qashqai.obd.obd.ObdTransport
import com.qashqai.obd.scan.CanCapture
import com.qashqai.obd.scan.EcuScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/** Scripted ELM327: answers each command via [reply]; ATMA streams [busFrames] until any byte arrives. */
private class FakeElm(private val busFrames: List<String> = emptyList(), private val reply: (String) -> String) : ObdTransport {
    val commands = mutableListOf<String>()
    private val queue = LinkedBlockingQueue<Int>()
    @Volatile private var monitoring = false
    private val cmd = StringBuilder()

    private fun send(s: String) = s.forEach { queue.put(it.code) }

    override val input = object : InputStream() {
        override fun available() = queue.size
        override fun read() = queue.take()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            var n = 0
            while (n < len && queue.isNotEmpty()) { b[off + n] = queue.take().toByte(); n++ }
            return n
        }
    }

    override val output = object : OutputStream() {
        override fun write(b: Int) {
            if (monitoring) { monitoring = false; send("\r>"); return }
            val c = b.toChar()
            if (c != '\r') { cmd.append(c); return }
            val command = cmd.toString(); cmd.setLength(0)
            commands += command
            if (command == "ATMA") { monitoring = true; busFrames.forEach { send("$it\r") }; return }
            send(reply(command) + "\r\r>")
        }
    }

    override fun close() {}
}

class ScanToolsTest {
    private fun car(cmd: String): String = when {
        cmd == "ATI" -> "ELM327 v1.5"
        cmd == "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
        cmd == "ATDPN" -> "A6"
        cmd == "ATRV" -> "14.1V"
        cmd.startsWith("AT") -> "OK"
        cmd == "0100" -> "7E8 06 41 00 98 18 80 11"
        cmd.startsWith("01") -> "7E8 04 41 ${cmd.substring(2)} 12 34"
        cmd == "0902" -> "7E8 10 14 49 02 01 53 4A 4E\r7E8 21 46 41 41 4A 31 30 55\r7E8 22 31 32 33 34 35 36 37"
        cmd.startsWith("09") -> "NO DATA"
        cmd == "10C0" -> "7E8 02 50 C0"
        cmd == "1081" -> "7E8 02 50 81"
        cmd == "2101" -> "7E8 10 0A 61 01 11 22 33 44\r7E8 21 55 66 77 88 00 00 00"
        cmd == "2180" -> "7E8 06 61 80 38 32 30 30"
        cmd.startsWith("21") -> "7E8 03 7F 21 12"
        cmd.startsWith("22") -> "7E8 03 7F 22 11"
        else -> "?"
    }

    @Test fun ecuScanFindsManufacturerBlocksAndRestoresAdapter() = runBlocking {
        val fake = FakeElm(reply = ::car)
        val elm = Elm327(fake).apply { isCan = true; verbose = false }
        val r = EcuScanner(elm, setOf(0x04, 0x0C), progress = { _, _ -> }).run()

        assertTrue(r.summary.toString(), r.summary.contains("VIN: SJNFAAJ10U1234567"))
        assertTrue(r.summary.contains("Engine diagnostic session (10 C0): accepted"))
        assertTrue(r.summary.toString(), r.summary.contains("Manufacturer blocks (21 xx): 2 found – 01 80"))
        assertTrue(r.report.contains("= 7E8: 61 01 11 22 33 44 55 66 77 88"))
        assertTrue(r.report.contains("\"a.8200\""))
        // service 22 not supported: gave up after 8 requests
        assertEquals(8, fake.commands.count { it.startsWith("22") })
        // every 21 id asked, and the adapter put back the way polling expects it
        assertEquals(254, fake.commands.count { it.startsWith("21") })
        assertEquals(listOf("1081", "ATCRA", "ATAR", "ATSH7DF", "ATH0", "ATS0"), fake.commands.takeLast(6))
    }

    @Test fun canCaptureSummarisesTraffic() = runBlocking {
        val frames = (0 until 120).map { i -> "551 %02X 20 %02X 00".format(i and 0x0F, 0x10 + i / 10) } +
            (0 until 40).map { "5C5 00 4E 20 00" }
        val fake = FakeElm(frames, ::car)
        val elm = Elm327(fake).apply { isCan = true; verbose = false }
        val r = CanCapture(elm) { _, _ -> }.run(300, listOf("820 rpm"))

        assertTrue(r.summary.toString(), r.summary[0].endsWith("160 frames, 2 message IDs"))
        assertTrue(r.summary.toString(), r.summary.contains("Rising counters in: 551 B2"))
        assertTrue(r.report.contains("Car at start: 820 rpm"))
        assertEquals(listOf("ATCAF1", "ATH0", "ATS0"), fake.commands.takeLast(3))
    }
}

package com.qashqai.obd

import com.qashqai.obd.obd.CanFrame
import com.qashqai.obd.obd.ElmParser
import com.qashqai.obd.obd.ScanParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanParserTest {
    @Test fun singleFrame() {
        val m = ScanParser.canMessages(listOf("7E8 06 41 00 BE 3E B8 11 00"), 1).single()
        assertEquals("7E8", m.header)
        assertEquals("41 00 BE 3E B8 11", ScanParser.hex(m.bytes))
        assertTrue(m.isPositiveFor(0x01))
    }

    @Test fun multiFrameVin() {
        val lines = listOf(
            "7E8 10 14 49 02 01 53 4A 4E",
            "7E8 21 46 41 41 4A 31 30 55",
            "7E8 22 31 32 33 34 35 36 37",
        )
        val msgs = ScanParser.canMessages(lines, 1)
        assertEquals(1, msgs.size)
        assertEquals(20, msgs[0].bytes.size)
        assertEquals("SJNFAAJ10U1234567", ScanParser.mode09Text(msgs, 0x02))
    }

    @Test fun negativeResponseAndNoise() {
        val msgs = ScanParser.canMessages(listOf("SEARCHING...", "7E8 03 7F 21 12 00 00 00 00", "NO DATA"), 1)
        val m = msgs.single()
        assertTrue(m.isNegative)
        assertEquals(0x12, m.nrc)
        assertFalse(m.isPositiveFor(0x21))
    }

    @Test fun extendedIds() {
        val (id, data) = ScanParser.frame("18 DA F1 10 03 61 01 55", 4)!!
        assertEquals("18DAF110", id)
        assertEquals(listOf(0x03, 0x61, 0x01, 0x55), data.toList())
        assertNull(ScanParser.frame("BUFFER FULL", 1))
        assertTrue(ElmParser.isCan29Bit("A7"))
        assertFalse(ElmParser.isCan29Bit("6"))
    }

    @Test fun counterDetection() {
        // 0x551: B0 = alive counter (every frame +1, 4-bit), B2 = fuel-like counter rising every 5th frame, B4 = noise
        var fuel = 0xF0
        val frames = (0 until 200).map { i ->
            if (i % 5 == 0) fuel = (fuel + 1) and 0xFF
            CanFrame(i * 10L, "551", intArrayOf(i and 0x0F, 0x20, fuel, 0, listOf(3, 9, 1, 7)[i % 4]))
        }
        val s = ScanParser.summarize(frames, 2000).single()
        assertEquals(100.0, s.hz, 0.01)
        assertTrue(s.isAliveCounter(0))
        assertEquals(listOf(2), s.counterLike)
    }

    @Test fun asciiOnlyForText() {
        assertEquals("8200.21", ScanParser.ascii("8200.21".map { it.code }.toIntArray()))
        assertNull(ScanParser.ascii(intArrayOf(0x61, 0x01, 0x00, 0x12, 0xFF)))
    }
}

package com.qashqai.obd

import com.qashqai.obd.obd.ElmParser
import com.qashqai.obd.obd.FuelModel
import com.qashqai.obd.obd.FuelMode
import com.qashqai.obd.obd.FuelParams
import com.qashqai.obd.obd.FuelSource
import com.qashqai.obd.obd.Pid
import com.qashqai.obd.obd.TripStats
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmParserTest {

    @Test fun cleansEchoPromptAndSearching() {
        val lines = ElmParser.cleanLines("010C\rSEARCHING...\r410C1AF8\r\r>", "010C")
        assertEquals(listOf("410C1AF8"), lines)
    }

    @Test fun parsesRpmWithAndWithoutSpaces() {
        for (raw in listOf("41 0C 1A F8", "410C1AF8")) {
            val data = ElmParser.parseMode01(ElmParser.cleanLines(raw), 0x0C)!!
            assertEquals(1726.0, Pid.RPM.decode(data)!!, 0.01)
        }
    }

    @Test fun picksMatchingPidAmongMultipleEcus() {
        val lines = ElmParser.cleanLines("410D00\r4105 7B\r")
        assertEquals(0x7B - 40.0, Pid.COOLANT_TEMP.decode(ElmParser.parseMode01(lines, 0x05)!!)!!, 0.0)
    }

    @Test fun recognisesProblems() {
        assertEquals(ElmParser.Problem.NoData, ElmParser.problem(ElmParser.cleanLines("NO DATA\r>")))
        assertTrue(ElmParser.problem(ElmParser.cleanLines("SEARCHING...\rUNABLE TO CONNECT\r>")) is ElmParser.Problem.Bus)
        assertEquals(ElmParser.Problem.Unsupported, ElmParser.problem(listOf("?")))
        assertNull(ElmParser.problem(listOf("410C1AF8")))
    }

    @Test fun supportedBitmap() {
        // Real-world reply to 0100: BE 1F A8 13
        val s = ElmParser.decodeSupported(0, intArrayOf(0xBE, 0x1F, 0xA8, 0x13))
        assertEquals(setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20), s)
        // second range is offset by the base
        assertEquals(setOf(0x21, 0x40), ElmParser.decodeSupported(0x20, intArrayOf(0x80, 0x00, 0x00, 0x01)))
    }

    @Test fun canProtocolDetection() {
        assertTrue(ElmParser.isCanProtocol("A6"))
        assertTrue(ElmParser.isCanProtocol("6"))
        assertTrue(!ElmParser.isCanProtocol("A3"))
        assertTrue(!ElmParser.isCanProtocol("5"))
    }

    @Test fun dtcDecoding() {
        assertEquals("P0401", ElmParser.decodeDtc(0x04, 0x01))
        assertEquals("C0300", ElmParser.decodeDtc(0x43, 0x00))
        assertEquals("U0100", ElmParser.decodeDtc(0xC1, 0x00))
    }

    @Test fun dtcCanSingleFrame() {
        assertEquals(listOf("P0133", "P0244"), ElmParser.parseDtcs(listOf("43020133 0244"), isCan = true))
        assertEquals(emptyList<String>(), ElmParser.parseDtcs(listOf("4300"), isCan = true))
    }

    @Test fun dtcCanMultiFrame() {
        val lines = listOf("00A", "0: 43 04 01 33 02 44", "1: 04 01 00 01 00 00 00")
        assertEquals(listOf("P0133", "P0244", "P0401", "P0001"), ElmParser.parseDtcs(lines, isCan = true))
    }

    @Test fun dtcLegacyProtocol() {
        assertEquals(listOf("P0133", "P0244"), ElmParser.parseDtcs(listOf("43 01 33 02 44 00 00"), isCan = false))
    }

    @Test fun voltage() {
        assertEquals(12.6, ElmParser.parseVoltage(listOf("12.6V"))!!, 0.001)
    }

    @Test fun hexRejectsText() {
        assertNull(ElmParser.hexBytes("OK"))
        assertNull(ElmParser.hexBytes("00A"))
        assertArrayEquals(intArrayOf(0x43, 0x01), ElmParser.hexBytes("0: 43 01"))
    }
}

class FuelModelTest {
    private val p = FuelParams()

    @Test fun autoPrefersFuelRateThenLoad() {
        assertEquals(FuelSource.FUEL_RATE_PID, FuelModel.pickSource(FuelMode.AUTO, setOf(0x5E, 0x04, 0x0C)))
        assertEquals(FuelSource.LOAD_MODEL, FuelModel.pickSource(FuelMode.AUTO, setOf(0x04, 0x0C, 0x10)))
        assertEquals(FuelSource.MAF_MODEL, FuelModel.pickSource(FuelMode.AUTO, setOf(0x10)))
        assertEquals(FuelSource.NONE, FuelModel.pickSource(FuelMode.AUTO, emptySet()))
    }

    @Test fun loadModelGivesPlausibleDieselNumbers() {
        // idle: ~800 rpm, ~15 % load -> should be well under 1.5 L/h
        val idle = FuelModel.litersPerHour(FuelSource.LOAD_MODEL, mapOf(Pid.RPM to 800.0, Pid.ENGINE_LOAD to 15.0), p)!!
        assertTrue("idle $idle", idle in 0.4..1.5)
        // 100 km/h cruise: ~2300 rpm, ~37 % load -> ~5–6 L/100km
        val cruise = FuelModel.litersPerHour(FuelSource.LOAD_MODEL, mapOf(Pid.RPM to 2300.0, Pid.ENGINE_LOAD to 37.0), p)!!
        val l100 = FuelModel.litersPer100km(cruise, 100.0)!!
        assertTrue("cruise $l100", l100 in 4.5..7.0)
        // overrun fuel cut
        assertEquals(0.0, FuelModel.litersPerHour(FuelSource.LOAD_MODEL, mapOf(Pid.RPM to 2000.0, Pid.ENGINE_LOAD to 0.0), p)!!, 0.0)
    }

    @Test fun calibrationScales() {
        val v = mapOf(Pid.RPM to 2000.0, Pid.ENGINE_LOAD to 30.0)
        val a = FuelModel.litersPerHour(FuelSource.LOAD_MODEL, v, p)!!
        val b = FuelModel.litersPerHour(FuelSource.LOAD_MODEL, v, p.copy(calibration = 1.1))!!
        assertEquals(a * 1.1, b, 1e-9)
    }

    @Test fun engineOffIsZero() {
        assertEquals(0.0, FuelModel.litersPerHour(FuelSource.LOAD_MODEL, mapOf(Pid.RPM to 0.0, Pid.ENGINE_LOAD to 20.0), p)!!, 0.0)
    }

    @Test fun noL100WhenStopped() {
        assertNull(FuelModel.litersPer100km(0.8, 0.0))
    }

    @Test fun tripIntegration() {
        var t = TripStats()
        repeat(3600) { t = t.add(1.0, 100.0, 6.0, engineRunning = true) }
        assertEquals(100.0, t.distanceKm, 1e-6)
        assertEquals(6.0, t.fuelL, 1e-6)
        assertEquals(6.0, t.avgL100!!, 1e-6)
        assertEquals(100.0, t.avgSpeedKmh!!, 1e-6)
    }
}

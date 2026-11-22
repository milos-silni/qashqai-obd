package com.qashqai.obd

import com.qashqai.obd.trip.Geo
import com.qashqai.obd.trip.RouteColors
import com.qashqai.obd.trip.TrackPoint
import com.qashqai.obd.trip.TripSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTest {
    /** ~111 m per 0.001° latitude */
    private fun track(fuelPer100mL: List<Double>, obd: Boolean = true): List<TrackPoint> {
        var fuel = 0.0
        val pts = mutableListOf(TrackPoint(0, 44.0, 20.0, 50f, 0.0, obd))
        fuelPer100mL.forEachIndexed { i, f ->
            fuel += f
            pts += TrackPoint((i + 1) * 1000L, 44.0 + (i + 1) * 0.0009, 20.0, 50f, fuel, obd)  // ~100 m steps
        }
        return pts
    }

    @Test fun distance() {
        // Belgrade -> Novi Sad is ~70 km in a straight line
        val d = Geo.distanceM(44.8125, 20.4612, 45.2671, 19.8335) / 1000
        assertTrue("$d", d in 68.0..72.0)
        assertEquals(100.0, Geo.distanceM(44.0, 20.0, 44.0009, 20.0), 1.0)
    }

    @Test fun csvRoundTrip() {
        val p = TrackPoint(1732300000000, 44.812345, 20.461234, 87.5f, 1.2345, true)
        assertEquals(p, TrackPoint.parse(p.toCsv()))
        assertNull(TrackPoint.parse("garbage"))
    }

    @Test fun buckets() {
        assertEquals(0, RouteColors.bucket(3.0, 5.0, 10.0))
        assertEquals(0, RouteColors.bucket(5.0, 5.0, 10.0))
        assertEquals(2, RouteColors.bucket(7.5, 5.0, 10.0))
        assertEquals(4, RouteColors.bucket(10.0, 5.0, 10.0))
        assertEquals(4, RouteColors.bucket(25.0, 5.0, 10.0))
        assertEquals(RouteColors.NO_DATA, RouteColors.bucket(null, 5.0, 10.0))
    }

    @Test fun economicalStretchIsGreenThenHeavyIsRed() {
        // 0.004 L per 100 m = 4 L/100km for 600 m, then 0.015 L per 100 m = 15 L/100km for 600 m
        val pts = track(List(6) { 0.004 } + List(6) { 0.015 })
        val lines = RouteColors.build(pts, green = 5.0, red = 10.0)
        assertEquals(listOf(0, 4), lines.map { it.bucket })
        // no gap: the red line starts where the green one ends
        assertEquals(lines[0].points.last(), lines[1].points.first())
        // every point is drawn
        assertEquals(pts.size, lines.sumOf { it.points.size } - (lines.size - 1))
    }

    @Test fun sameColourStretchesMerge() {
        val lines = RouteColors.build(track(List(20) { 0.004 }), 5.0, 10.0)
        assertEquals(1, lines.size)
        assertEquals(0, lines[0].bucket)
    }

    @Test fun withoutCarDataRouteIsGrey() {
        val lines = RouteColors.build(track(List(6) { 0.0 }, obd = false), 5.0, 10.0)
        assertEquals(listOf(RouteColors.NO_DATA), lines.map { it.bucket })
    }

    @Test fun summary() {
        val s = TripSummary.of(0, track(List(10) { 0.006 }))   // ~1 km at 6 L/100km
        assertEquals(1.0, s.distanceKm, 0.02)
        assertEquals(0.06, s.fuelL, 1e-9)
        assertEquals(6.0, s.avgL100!!, 0.15)
        assertEquals(10_000L, s.durationMs)
    }
}

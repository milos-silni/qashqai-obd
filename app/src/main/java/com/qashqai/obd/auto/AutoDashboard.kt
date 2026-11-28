package com.qashqai.obd.auto

import com.qashqai.obd.data.ConnState
import com.qashqai.obd.data.DashState
import com.qashqai.obd.obd.Pid
import com.qashqai.obd.trip.RecordingState
import com.qashqai.obd.trip.TripSummary
import com.qashqai.obd.ui.fmt
import com.qashqai.obd.ui.formatDuration

/** One line-up of the Android Auto screen; kept as plain text so it is easy to test. */
data class AutoRow(val title: String, val line1: String, val line2: String)

/**
 * What the car screen shows. Always the same four rows in the same order: Android Auto only
 * treats a new template as a cheap "refresh" when its structure stays the same.
 */
object AutoDashboard {
    fun rows(car: DashState, rec: RecordingState): List<AutoRow> {
        val connected = car.conn == ConnState.CONNECTED
        val v = car.values
        val speed = v[Pid.SPEED]

        val fuel = when {
            !connected -> AutoRow("Fuel now", "—", "Not connected")
            car.instantL100 != null -> AutoRow("Fuel now", "${car.instantL100.fmt(1)} L/100km",
                "${car.instantLph.fmt(1)} L/h · ${speed.fmt()} km/h")
            else -> AutoRow("Fuel now", "${car.instantLph.fmt(1)} L/h",
                if ((speed ?: 0.0) < 5) "Standing" else "${speed.fmt()} km/h")
        }

        val t = car.trip
        val trip = AutoRow(
            "Trip",
            t.avgL100?.let { "${it.fmt(1)} L/100km average" } ?: "Average after 0.5 km",
            "${t.distanceKm.fmt(1)} km · ${t.fuelL.fmt(2)} L · ${formatDuration(t.engineTimeS)} h",
        )

        val boostBar = v[Pid.INTAKE_MAP]?.let { map -> (map - (v[Pid.BARO] ?: 101.0)) / 100.0 }
        val engine = AutoRow(
            "Engine",
            if (connected) "${v[Pid.RPM].fmt()} rpm · ${v[Pid.COOLANT_TEMP].fmt()} °C" else "—",
            if (connected) "Boost ${boostBar?.coerceAtLeast(0.0).fmt(1)} bar · Battery ${car.batteryV.fmt(1)} V" else "",
        )

        val recLine = when {
            rec.recording -> "● Recording ${TripSummary.of(rec.tripId ?: 0, rec.points).distanceKm.fmt(1)} km" +
                if (!rec.gpsFix) " · waiting for GPS" else ""
            else -> "Trip map not recording"
        }
        val status = AutoRow("Car", car.status, recLine)
        return listOf(fuel, trip, engine, status)
    }
}

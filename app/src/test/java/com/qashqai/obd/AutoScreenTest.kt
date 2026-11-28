package com.qashqai.obd

import androidx.car.app.model.PaneTemplate
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import com.qashqai.obd.auto.AutoDashboard
import com.qashqai.obd.auto.DashboardCarScreen
import com.qashqai.obd.data.ConnState
import com.qashqai.obd.data.DashState
import com.qashqai.obd.obd.Pid
import com.qashqai.obd.obd.TripStats
import com.qashqai.obd.trip.RecordingState
import com.qashqai.obd.trip.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoScreenTest {
    private val driving = DashState(
        conn = ConnState.CONNECTED,
        status = "Connected",
        values = mapOf(Pid.SPEED to 92.0, Pid.RPM to 2100.0, Pid.COOLANT_TEMP to 88.0, Pid.INTAKE_MAP to 160.0, Pid.BARO to 100.0),
        batteryV = 14.1,
        instantLph = 5.4,
        instantL100 = 5.87,
        trip = TripStats(distanceKm = 42.31, fuelL = 2.456, engineTimeS = 3720.0, movingTimeS = 3500.0),
    )

    @Test fun rowsWhileDriving() {
        val rec = RecordingState(recording = true, tripId = 1, gpsFix = true, points = listOf(
            TrackPoint(0, 44.0, 20.0, 90f, 0.0, true), TrackPoint(1000, 44.009, 20.0, 90f, 0.05, true)))
        val rows = AutoDashboard.rows(driving, rec)
        assertEquals(listOf("Fuel now", "Trip", "Engine", "Car"), rows.map { it.title })
        assertEquals("5.9 L/100km", rows[0].line1)
        assertEquals("5.4 L/h · 92 km/h", rows[0].line2)
        assertEquals("5.8 L/100km average", rows[1].line1)
        assertEquals("42.3 km · 2.46 L · 1:02 h", rows[1].line2)
        assertEquals("2100 rpm · 88 °C", rows[2].line1)
        assertEquals("Boost 0.6 bar · Battery 14.1 V", rows[2].line2)
        assertEquals("● Recording 1.0 km", rows[3].line2)
    }

    @Test fun rowsWhenNotConnected() {
        val rows = AutoDashboard.rows(DashState(), RecordingState())
        assertEquals(4, rows.size)
        assertEquals("Not connected", rows[0].line2)
        assertEquals("Trip map not recording", rows[3].line2)
    }

    @Test fun carScreenBuildsAValidPane() {
        val app = ApplicationProvider.getApplicationContext<ObdApp>()
        val screen = DashboardCarScreen(TestCarContext.createCarContext(app), app.obd, app.trips)
        // build() enforces Android Auto's limits (rows, text lines, actions); it throws if we break them
        val pane = (screen.onGetTemplate() as PaneTemplate).pane!!
        assertEquals(4, pane.rows.size)
        assertEquals(listOf("Connect", "Record trip"), pane.actions.map { it.title.toString() })
        assertTrue(pane.rows[0].texts.any { it.toString() == "Not connected" })
    }
}

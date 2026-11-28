package com.qashqai.obd.auto

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qashqai.obd.data.ObdManager
import com.qashqai.obd.trip.TripRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The single Android Auto screen: live fuel, trip, engine and connection, plus connect/record buttons. */
class DashboardCarScreen(
    carContext: CarContext,
    private val obd: ObdManager,
    private val trips: TripRecorder,
) : Screen(carContext) {

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Redraw at most about once a second; the host may throttle faster refreshes.
                combine(obd.state, trips.state) { car, rec -> AutoDashboard.rows(car, rec) to labels() }
                    .distinctUntilChanged()
                    .conflate()
                    .collect { invalidate(); delay(REFRESH_MS) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Recorder messages ("GPS is turned off", "Trip saved") as toasts on the car screen.
                trips.state.map { it.message }.distinctUntilChanged().drop(1).collect { msg ->
                    if (msg != null) CarToast.makeText(carContext, msg, CarToast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun labels() = (if (obd.state.value.sessionActive) "Disconnect" else "Connect") to
        (if (trips.state.value.recording) "Stop recording" else "Record trip")

    @Suppress("DEPRECATION")   // setTitle/setHeaderAction work on every Android Auto version; setHeader needs a newer host
    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
        AutoDashboard.rows(obd.state.value, trips.state.value).forEach { r ->
            pane.addRow(Row.Builder().setTitle(r.title).addText(r.line1).apply { if (r.line2.isNotEmpty()) addText(r.line2) }.build())
        }
        val (connectLabel, recordLabel) = labels()
        pane.addAction(Action.Builder().setTitle(connectLabel).setOnClickListener(::toggleConnection).build())
        pane.addAction(Action.Builder().setTitle(recordLabel).setOnClickListener(::toggleRecording).build())
        return PaneTemplate.Builder(pane.build())
            .setTitle("Qashqai OBD")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun toggleConnection() {
        when {
            obd.state.value.sessionActive -> obd.disconnect()
            !obd.isConfigured() -> toast("Choose the OBD adapter in the app on your phone first")
            else -> obd.connect()
        }
        invalidate()
    }

    private fun toggleRecording() {
        if (trips.state.value.recording) trips.stop() else trips.start()
        invalidate()
    }

    private fun toast(text: String) = CarToast.makeText(carContext, text, CarToast.LENGTH_LONG).show()

    private companion object { const val REFRESH_MS = 1000L }
}

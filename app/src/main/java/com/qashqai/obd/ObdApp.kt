package com.qashqai.obd

import android.app.Application
import com.qashqai.obd.data.ObdManager
import com.qashqai.obd.trip.TripRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.io.File

class ObdApp : Application() {
    lateinit var obd: ObdManager
        private set
    lateinit var trips: TripRecorder
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().apply {
            userAgentValue = packageName                     // required by the OpenStreetMap tile policy
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        }
        obd = ObdManager(this)
        trips = TripRecorder(this, obd)

        // Foreground service while connected to the car or recording a trip.
        appScope.launch {
            combine(obd.state, trips.state) { car, rec -> car.sessionActive to rec.recording }
                .distinctUntilChanged()
                .collect { (session, recording) -> TripService.update(this@ObdApp, session || recording) }
        }
    }
}

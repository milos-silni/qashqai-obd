package com.qashqai.obd.auto

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.qashqai.obd.ObdApp

/**
 * Entry point for Android Auto. The app isn't from the Play Store, so Android Auto only lists it with
 * "Unknown sources" turned on in Android Auto's developer settings.
 */
class ObdCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)   // Android Auto + Android Automotive hosts
            .build()

    @Deprecated("Deprecated in Java")
    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            val app = application as ObdApp
            // Opening the app in the car should be enough: connect like the phone app does on launch.
            val s = app.obd.settings.value
            if (s.autoConnect && app.obd.isConfigured(s) && !app.obd.isRunning) app.obd.connect()
            return DashboardCarScreen(carContext, app.obd, app.trips)
        }
    }
}

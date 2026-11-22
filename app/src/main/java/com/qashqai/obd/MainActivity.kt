package com.qashqai.obd

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.qashqai.obd.data.ConnState
import com.qashqai.obd.ui.AppRoot
import com.qashqai.obd.ui.QashqaiTheme

class MainActivity : ComponentActivity() {

    private val obd get() = (application as ObdApp).obd

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            QashqaiTheme {
                AppRoot(
                    obd = obd,
                    trips = (application as ObdApp).trips,
                    hasBluetoothPermission = ::hasBluetoothPermission,
                    requestPermissions = ::requestPermissions,
                )
            }
        }

        if (missingPermissions().isNotEmpty()) requestPermissions() else maybeAutoConnect()
    }

    private fun onPermissionsResult() {
        val app = application as ObdApp
        // service may now be allowed (or allowed to use GPS); auto-record may have been blocked by missing location access
        TripService.update(this, obd.state.value.sessionActive || app.trips.state.value.recording)
        if (obd.state.value.conn == ConnState.CONNECTED && obd.settings.value.autoRecord) app.trips.start(auto = true)
        maybeAutoConnect()
    }

    private fun maybeAutoConnect() {
        val s = obd.settings.value
        if (s.autoConnect && obd.isConfigured(s) && !obd.isRunning) obd.connect()
    }

    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Bluetooth (adapter), location (trip map) and notifications (recording indicator). */
    private fun missingPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    fun requestPermissions() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) permissionLauncher.launch(missing)
    }
}

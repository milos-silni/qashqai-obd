package com.qashqai.obd

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the app alive while a navigation app is on screen:
 * without it Android throttles background GPS to a few fixes per hour and may kill the OBD connection.
 * All the work is done by ObdManager / TripRecorder; this only holds the notification.
 */
class TripService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val types = foregroundTypes(this)
        if (Build.VERSION.SDK_INT >= 29 && types == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val recording = (application as ObdApp).trips.state.value.recording
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (recording) "Recording trip" else "Reading car data")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Car connection & trip recording", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        private const val CHANNEL = "trip"
        private const val NOTIFICATION_ID = 1

        private fun granted(context: Context, permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        /** Only claim service types whose permissions we hold, otherwise Android 14+ refuses to start. */
        fun foregroundTypes(context: Context): Int {
            if (Build.VERSION.SDK_INT < 29) return 0
            var types = 0
            if (granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (Build.VERSION.SDK_INT < 31 || granted(context, Manifest.permission.BLUETOOTH_CONNECT)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            return types
        }

        fun update(context: Context, run: Boolean) {
            val intent = Intent(context, TripService::class.java)
            if (!run) {
                context.stopService(intent)
                return
            }
            if (Build.VERSION.SDK_INT >= 29 && foregroundTypes(context) == 0) return
            // Can be refused when the app is in the background (Android 12+); the app then just isn't protected.
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}

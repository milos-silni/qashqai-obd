package com.qashqai.obd.trip

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.qashqai.obd.data.ConnState
import com.qashqai.obd.data.ObdManager
import com.qashqai.obd.obd.Pid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class RecordingState(
    val recording: Boolean = false,
    val tripId: Long? = null,
    /** Started by auto-record (car connected) rather than by the user. */
    val auto: Boolean = false,
    val points: List<TrackPoint> = emptyList(),
    val gpsFix: Boolean = false,
    val message: String? = null,
)

/**
 * Records the GPS track of a trip together with the fuel used along it.
 * With auto-record on, a trip starts when the car connects and ends once the car has been
 * disconnected for [AUTO_STOP_DELAY_MS] (ignition off), so short Bluetooth drop-outs don't split a trip.
 */
class TripRecorder(private val context: Context, private val obd: ObdManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val store = TripStore(context)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _trips = MutableStateFlow<List<TripSummary>>(emptyList())
    val trips: StateFlow<List<TripSummary>> = _trips.asStateFlow()

    private var lastObdTripFuel = 0.0
    private var fuelSinceStart = 0.0
    private var autoStopJob: Job? = null

    // All four methods implemented explicitly: on Android < 11 they are abstract in the framework
    // and a lambda listener would crash with AbstractMethodError.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) { _state.update { it.copy(gpsFix = false) } }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    init {
        refreshTrips()
        scope.launch {
            obd.state.map { it.conn == ConnState.CONNECTED }.distinctUntilChanged().collect(::onCarConnection)
        }
    }

    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(auto: Boolean = false) {
        if (_state.value.recording) return
        if (!hasLocationPermission()) {
            _state.update { it.copy(message = "Location permission is needed to record trips") }
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            _state.update { it.copy(message = "GPS is turned off") }
            return
        }
        lastObdTripFuel = obd.state.value.trip.fuelL
        fuelSinceStart = 0.0
        _state.value = RecordingState(recording = true, tripId = System.currentTimeMillis(), auto = auto)
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
    }

    fun stop() {
        autoStopJob?.cancel()
        autoStopJob = null
        val st = _state.value
        if (!st.recording) return
        locationManager.removeUpdates(listener)
        val saved = st.points.size >= 2
        _state.value = RecordingState(message = if (saved) "Trip saved" else "Trip too short – not saved")
        val id = st.tripId ?: return
        scope.launch(io) {
            if (!saved) store.delete(id)
            refreshTripsNow()
        }
    }

    suspend fun load(id: Long): List<TrackPoint> = withContext(io) { store.load(id) }

    fun delete(id: Long) = scope.launch(io) { store.delete(id); refreshTripsNow() }

    @SuppressLint("MissingPermission")
    fun lastKnownLocation(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null
        val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return loc.latitude to loc.longitude
    }

    private fun onLocation(loc: Location) {
        val st = _state.value
        val id = st.tripId
        if (!st.recording || id == null) return
        if (loc.hasAccuracy() && loc.accuracy > 40f) {
            _state.update { it.copy(gpsFix = false) }
            return
        }
        val last = st.points.lastOrNull()
        // Standing still: don't add points; the fuel burnt meanwhile goes to the next stretch.
        if (last != null && Geo.distanceM(last.lat, last.lon, loc.latitude, loc.longitude) < 8.0) {
            if (!st.gpsFix) _state.update { it.copy(gpsFix = true) }
            return
        }
        val car = obd.state.value
        val tripFuel = car.trip.fuelL
        val delta = tripFuel - lastObdTripFuel
        if (delta > 0) fuelSinceStart += delta   // negative = user reset the dashboard trip
        lastObdTripFuel = tripFuel

        val point = TrackPoint(
            t = System.currentTimeMillis(),
            lat = loc.latitude,
            lon = loc.longitude,
            speedKmh = car.values[Pid.SPEED]?.toFloat() ?: if (loc.hasSpeed()) loc.speed * 3.6f else 0f,
            fuelL = fuelSinceStart,
            obd = car.conn == ConnState.CONNECTED && car.instantLph != null,
        )
        _state.update { it.copy(points = it.points + point, gpsFix = true) }
        scope.launch(io) { store.append(id, point) }
    }

    private fun onCarConnection(connected: Boolean) {
        val st = _state.value
        if (connected) {
            autoStopJob?.cancel()
            autoStopJob = null
            if (!st.recording && obd.settings.value.autoRecord) start(auto = true)
        } else if (st.recording && st.auto) {
            autoStopJob?.cancel()
            autoStopJob = scope.launch { delay(AUTO_STOP_DELAY_MS); stop() }
        }
    }

    private fun refreshTrips() = scope.launch(io) { refreshTripsNow() }
    private fun refreshTripsNow() { _trips.value = store.list() }

    companion object {
        const val AUTO_STOP_DELAY_MS = 2 * 60_000L
    }
}

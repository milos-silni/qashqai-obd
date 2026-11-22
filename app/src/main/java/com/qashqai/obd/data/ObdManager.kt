package com.qashqai.obd.data

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.qashqai.obd.obd.BluetoothTransport
import com.qashqai.obd.obd.Elm327
import com.qashqai.obd.obd.ElmBusException
import com.qashqai.obd.obd.ElmParser
import com.qashqai.obd.obd.ElmTimeoutException
import com.qashqai.obd.obd.FuelModel
import com.qashqai.obd.obd.FuelSource
import com.qashqai.obd.obd.ObdTransport
import com.qashqai.obd.obd.Pid
import com.qashqai.obd.obd.TripStats
import com.qashqai.obd.scan.CanCapture
import com.qashqai.obd.scan.EcuScanner
import com.qashqai.obd.scan.ScanReports
import com.qashqai.obd.scan.ScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A connection error that retrying won't fix (needs the user), so auto-reconnect stops. */
class NeedsUserException(message: String) : IOException(message)

enum class ConnState { DISCONNECTED, CONNECTING, INITIALIZING, CONNECTED, ERROR }

data class DashState(
    val conn: ConnState = ConnState.DISCONNECTED,
    /** True while a connect/auto-reconnect session is running (drives the Connect/Disconnect button). */
    val sessionActive: Boolean = false,
    val status: String = "Not connected",
    val adapterVersion: String? = null,
    val protocol: String? = null,
    val supported: Set<Int> = emptySet(),
    val values: Map<Pid, Double> = emptyMap(),
    val batteryV: Double? = null,
    val fuelSource: FuelSource = FuelSource.NONE,
    val instantLph: Double? = null,
    val instantL100: Double? = null,
    val trip: TripStats = TripStats(),
    /** Since last "full tank" mark – used for fill-up calibration. */
    val fill: TripStats = TripStats(),
    val dtcs: List<String>? = null,
    val dtcStatus: String? = null,
    val log: List<String> = emptyList(),
)

/** ECU scan / CAN capture tool state. */
data class ScanState(
    /** Name of the tool running now, null when idle. */
    val busy: String? = null,
    val progress: Float? = null,
    val status: String? = null,
    val summary: List<String> = emptyList(),
    val reports: List<File> = emptyList(),
)

/** Owns the adapter connection and polling loop. Lives in the Application so it survives screen changes. */
class ObdManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = Prefs(context)

    private val _settings = MutableStateFlow(prefs.loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _state = MutableStateFlow(DashState(trip = prefs.loadTrip(TRIP), fill = prefs.loadTrip(FILL)))
    val state: StateFlow<DashState> = _state.asStateFlow()

    val reports = ScanReports(context)
    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()
    private var scanJob: Job? = null
    /** Held by the poll loop for each round and by the scan tools for their whole run, so they never interleave. */
    private val busGate = Mutex()

    private var session: Job? = null
    private var generation = 0
    @Volatile private var elm: Elm327? = null

    val isRunning: Boolean get() = session?.isActive == true

    fun updateSettings(f: (AppSettings) -> AppSettings) {
        _settings.update(f)
        prefs.saveSettings(_settings.value)
    }

    init {
        scope.launch(Dispatchers.IO) { refreshReports() }
    }

    fun isConfigured(s: AppSettings = settings.value) = s.btAddress != null

    fun connect() {
        if (isRunning) return
        val gen = ++generation
        _state.update { it.copy(sessionActive = true) }
        session = scope.launch {
            try {
                sessionLoop()
            } finally {
                if (gen == generation) _state.update { it.copy(sessionActive = false) }
            }
        }
    }

    fun disconnect() {
        generation++
        scanJob?.cancel()
        session?.cancel()
        session = null
        _state.update {
            it.copy(conn = ConnState.DISCONNECTED, sessionActive = false, status = "Disconnected",
                values = emptyMap(), batteryV = null, instantLph = null, instantL100 = null)
        }
    }

    fun reconnect() { disconnect(); connect() }

    // ---- trips & calibration --------------------------------------------

    fun resetTrip() {
        _state.update { it.copy(trip = TripStats()) }
        prefs.saveTrip(TRIP, TripStats())
    }

    fun markFullTank() {
        _state.update { it.copy(fill = TripStats()) }
        prefs.saveTrip(FILL, TripStats())
    }

    /** Returns an error message, or null on success. */
    fun calibrateFromFillUp(litersAdded: Double): String? {
        val measured = state.value.fill.fuelL
        if (measured < 2.0) return "Need at least 2 L measured since the last full tank (now ${"%.1f".format(Locale.US, measured)} L)."
        if (litersAdded <= 0) return "Enter the litres you put in."
        val old = settings.value.fuel.calibration
        val new = (old * litersAdded / measured).coerceIn(0.3, 3.0)
        updateSettings { it.copy(fuel = it.fuel.copy(calibration = new)) }
        markFullTank()
        log(String.format(Locale.US, "Calibration %.3f -> %.3f (pump %.2f L / app %.2f L)", old, new, litersAdded, measured))
        return null
    }

    // ---- diagnostics -----------------------------------------------------

    fun readDtcs() = scope.launch {
        val e = elm ?: run { _state.update { it.copy(dtcStatus = "Connect to the car first") }; return@launch }
        _state.update { it.copy(dtcStatus = "Reading…") }
        try {
            val codes = busGate.withLock { e.readDtcs() }
            _state.update { it.copy(dtcs = codes, dtcStatus = if (codes.isEmpty()) "No stored trouble codes" else "${codes.size} code(s) stored") }
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            _state.update { it.copy(dtcStatus = "Read failed: ${ex.message}") }
        }
    }

    fun clearDtcs() = scope.launch {
        val e = elm ?: run { _state.update { it.copy(dtcStatus = "Connect to the car first") }; return@launch }
        try {
            val ok = busGate.withLock { e.clearDtcs() }
            _state.update { it.copy(dtcs = if (ok) emptyList() else it.dtcs, dtcStatus = if (ok) "Codes cleared" else "ECU did not confirm clear (engine must be off, ignition on)") }
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            _state.update { it.copy(dtcStatus = "Clear failed: ${ex.message}") }
        }
    }

    // ---- ECU scan / CAN capture --------------------------------------------

    fun scanEcu() = runTool("ECU scan", "ecu-scan") { e, progress ->
        EcuScanner(e, state.value.supported, progress).run()
    }

    fun captureCan(seconds: Int) = runTool("CAN capture", "can-capture") { e, progress ->
        val v = state.value.values
        val carNow = listOfNotNull(
            v[Pid.RPM]?.let { "%.0f rpm".format(Locale.US, it) },
            v[Pid.SPEED]?.let { "%.0f km/h".format(Locale.US, it) },
            v[Pid.ENGINE_LOAD]?.let { "load %.0f%%".format(Locale.US, it) },
        )
        CanCapture(e, progress).run(seconds * 1000L, carNow)
    }

    fun cancelScan() { scanJob?.cancel() }

    fun deleteReport(f: File) = scope.launch(Dispatchers.IO) { f.delete(); refreshReports() }

    private fun refreshReports() = _scan.update { it.copy(reports = reports.list()) }

    private fun runTool(name: String, kind: String, block: suspend (Elm327, (String, Float?) -> Unit) -> ScanResult) {
        if (scanJob?.isActive == true) return
        val e = elm ?: run { _scan.update { it.copy(status = "Connect to the car first") }; return }
        _scan.update { it.copy(busy = name, progress = 0f, status = "Starting…", summary = emptyList()) }
        scanJob = scope.launch {
            try {
                val result = busGate.withLock {
                    block(e) { text, p -> _scan.update { it.copy(status = text, progress = p) } }
                }
                val file = withContext(Dispatchers.IO) { reports.save(kind, result.report) }
                log("$name saved to ${file.name}")
                _scan.update { it.copy(status = "$name saved: ${file.name}", summary = result.summary) }
                withContext(Dispatchers.IO) { refreshReports() }
            } catch (c: CancellationException) {
                _scan.update { it.copy(status = "$name stopped") }
                throw c
            } catch (ex: Exception) {
                log("$name failed: ${ex.message}")
                _scan.update { it.copy(status = "$name failed: ${ex.message}") }
            } finally {
                _scan.update { it.copy(busy = null, progress = null) }
            }
        }
    }

    // ---- session ---------------------------------------------------------

    private suspend fun sessionLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val s = settings.value
            var transport: ObdTransport? = null
            try {
                setConn(ConnState.CONNECTING, "Connecting to ${describe(s)}…")
                transport = openTransport(s)
                val e = Elm327(transport, ::log)
                initialize(e, s)
                elm = e
                attempt = 0
                pollLoop(e)
            } catch (c: CancellationException) {
                throw c
            } catch (ex: Exception) {
                log("Error: ${ex.message}")
                setConn(ConnState.ERROR, ex.message ?: ex.javaClass.simpleName)
                if (ex is NeedsUserException) break
            } finally {
                elm = null
                transport?.let { runCatching { it.close() } }
                saveTrips()
            }
            if (!settings.value.autoConnect) break
            attempt++
            val waitMs = minOf(3000L * attempt, 15000L)
            _state.update { it.copy(status = "${it.status} – retry in ${waitMs / 1000}s", values = emptyMap(), batteryV = null, instantLph = null, instantL100 = null) }
            delay(waitMs)
        }
    }

    private fun describe(s: AppSettings) = s.btName ?: s.btAddress ?: "Bluetooth adapter"

    private suspend fun openTransport(s: AppSettings): ObdTransport = withContext(Dispatchers.IO) {
        val address = s.btAddress ?: throw NeedsUserException("Choose your OBD adapter in Settings")
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) throw NeedsUserException("Bluetooth permission not granted")
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter ?: throw IOException("This device has no Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth is turned off")
        BluetoothTransport.connect(adapter, address)
    }

    private suspend fun initialize(e: Elm327, s: AppSettings) {
        setConn(ConnState.INITIALIZING, "Initializing adapter…")
        val version = runCatching { e.at("ATZ", 5000) }.getOrElse {
            // some clones ignore ATZ right after connect; try a warm start
            e.at("ATWS", 5000)
        }
        delay(200)
        e.at("ATE0")   // echo off
        e.at("ATL0")   // no linefeeds
        e.at("ATS0")   // no spaces
        e.at("ATH0")   // no headers
        runCatching { e.at("ATAT1") } // adaptive timing (unsupported on some clones)
        e.at("ATSP${s.protocol}")

        _state.update { it.copy(adapterVersion = version.ifBlank { null }) }
        setConn(ConnState.INITIALIZING, "Talking to engine ECU… (ignition must be ON)")

        val supported = mutableSetOf<Int>()
        var base = 0
        while (true) {
            val replies = try {
                e.queryMode01All(base, timeoutMs = if (base == 0) 15000 else 4000, allowSuffix = false)
            } catch (ex: ElmBusException) {
                if (base == 0) throw IOException("ECU not responding (${ex.message}) – is the ignition on?") else emptyList()
            } catch (ex: ElmTimeoutException) {
                if (base == 0) throw IOException("ECU not responding – is the ignition on?") else emptyList()
            }
            if (replies.isEmpty()) {
                if (base == 0) throw IOException("ECU sent no data – is the ignition on?")
                break
            }
            replies.forEach { supported += ElmParser.decodeSupported(base, it) }
            if ((base + 0x20) !in supported || base >= 0xA0) break
            base += 0x20
        }

        val dp = runCatching { e.at("ATDP") }.getOrDefault("")
        val dpn = runCatching { e.at("ATDPN") }.getOrDefault("")
        e.isCan = ElmParser.isCanProtocol(dpn)
        e.canExtended = ElmParser.isCan29Bit(dpn)
        if (e.isCan) e.responseCountSuffix = e.probeResponseCountSuffix()
        log("Protocol: $dp ($dpn), CAN=${e.isCan}, fast replies=${e.responseCountSuffix}")
        log("Supported PIDs: " + supported.sorted().joinToString(" ") { String.format(Locale.US, "%02X", it) })

        _state.update {
            it.copy(
                protocol = dp.removePrefix("AUTO, ").ifBlank { null },
                supported = supported,
                fuelSource = FuelModel.pickSource(settings.value.fuelMode, supported),
            )
        }
    }

    private suspend fun pollLoop(e: Elm327) {
        val supported = state.value.supported
        val fast = Pid.FAST.filter { it.code in supported }
        val slow = Pid.SLOW.filter { it.code in supported }
        var slowIdx = 0
        var cycle = 0
        var failures = 0
        var lastTick = SystemClock.elapsedRealtime()
        var lastSave = lastTick
        var smoothLph: Double? = null
        e.verbose = false
        setConn(ConnState.CONNECTED, "Connected")

        while (true) {
            currentCoroutineContext().ensureActive()
            val values = HashMap(state.value.values)
            val batch = if (slow.isEmpty()) fast else fast + slow[slowIdx++ % slow.size]
            busGate.withLock { for (pid in batch) {
                try {
                    val v = e.queryPid(pid.code)?.let(pid::decode)
                    if (v != null) values[pid] = v else values.remove(pid)
                    failures = 0
                } catch (ex: ElmBusException) {
                    if (++failures >= 6) throw IOException("Lost contact with ECU (${ex.message})")
                } catch (ex: ElmTimeoutException) {
                    if (++failures >= 6) throw IOException("Adapter stopped answering")
                }
            } }
            val battery = if (cycle % 20 == 0) busGate.withLock { e.batteryVoltage() } ?: state.value.batteryV else state.value.batteryV
            cycle++

            // fuel + trip integration
            val s = settings.value
            val source = FuelModel.pickSource(s.fuelMode, supported)
            val lph = FuelModel.litersPerHour(source, values, s.fuel)
            val speed = values[Pid.SPEED] ?: 0.0
            val running = (values[Pid.RPM] ?: 0.0) > 300
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastTick) / 1000.0
            lastTick = now
            smoothLph = when {
                lph == null -> null
                smoothLph == null -> lph
                else -> smoothLph + 0.35 * (lph - smoothLph)
            }

            _state.update { st ->
                val ok = dt in 0.0..5.0 && lph != null
                st.copy(
                    values = values,
                    batteryV = battery,
                    fuelSource = source,
                    instantLph = smoothLph,
                    instantL100 = FuelModel.litersPer100km(smoothLph, speed),
                    trip = if (ok) st.trip.add(dt, speed, lph!!, running) else st.trip,
                    fill = if (ok) st.fill.add(dt, speed, lph!!, running) else st.fill,
                )
            }
            if (now - lastSave > 15_000) { saveTrips(); lastSave = now }
        }
    }

    private fun saveTrips() {
        prefs.saveTrip(TRIP, state.value.trip)
        prefs.saveTrip(FILL, state.value.fill)
    }

    private fun setConn(c: ConnState, status: String) = _state.update { it.copy(conn = c, status = status) }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun log(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        _state.update { it.copy(log = (it.log + line).takeLast(200)) }
    }

    private companion object {
        const val TRIP = "trip"
        const val FILL = "fill"
    }
}

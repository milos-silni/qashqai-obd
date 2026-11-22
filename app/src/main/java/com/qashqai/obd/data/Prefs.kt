package com.qashqai.obd.data

import android.content.Context
import android.content.SharedPreferences
import com.qashqai.obd.obd.FuelMode
import com.qashqai.obd.obd.FuelParams
import com.qashqai.obd.obd.TripStats

data class AppSettings(
    val btAddress: String? = null,
    val btName: String? = null,
    /** ELM327 ATSP value. "0" = auto, "6" = ISO 15765-4 CAN 11 bit 500 kbaud (what the J10 Qashqai uses). */
    val protocol: String = "0",
    val autoConnect: Boolean = true,
    val fuelMode: FuelMode = FuelMode.AUTO,
    val fuel: FuelParams = FuelParams(),
    /** Start a GPS trip when the car connects, end it when the car is off for a while. */
    val autoRecord: Boolean = true,
    /** Route colour scale: green at or below, red at or above (L/100km). */
    val greenBelow: Double = 5.0,
    val redAbove: Double = 10.0,
    val darkMap: Boolean = true,
)

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("obd", Context.MODE_PRIVATE)

    fun loadSettings() = AppSettings(
        btAddress = sp.getString("btAddress", null),
        btName = sp.getString("btName", null),
        protocol = sp.getString("protocol", null) ?: "0",
        autoConnect = sp.getBoolean("autoConnect", true),
        fuelMode = enumOr(sp.getString("fuelMode", null), FuelMode.AUTO),
        fuel = FuelParams(
            calibration = getDouble("calibration", 1.0),
            maxMgPerStroke = getDouble("maxMg", 45.0),
            mafAfr = getDouble("mafAfr", 30.0),
        ),
        autoRecord = sp.getBoolean("autoRecord", true),
        greenBelow = getDouble("greenBelow", 5.0),
        redAbove = getDouble("redAbove", 10.0),
        darkMap = sp.getBoolean("darkMap", true),
    )

    fun saveSettings(s: AppSettings) {
        sp.edit()
            .putString("btAddress", s.btAddress)
            .putString("btName", s.btName)
            .putString("protocol", s.protocol)
            .putBoolean("autoConnect", s.autoConnect)
            .putString("fuelMode", s.fuelMode.name)
            .putDouble("calibration", s.fuel.calibration)
            .putDouble("maxMg", s.fuel.maxMgPerStroke)
            .putDouble("mafAfr", s.fuel.mafAfr)
            .putBoolean("autoRecord", s.autoRecord)
            .putDouble("greenBelow", s.greenBelow)
            .putDouble("redAbove", s.redAbove)
            .putBoolean("darkMap", s.darkMap)
            .apply()
    }

    fun loadTrip(key: String) = TripStats(
        distanceKm = getDouble("$key.km", 0.0),
        fuelL = getDouble("$key.l", 0.0),
        engineTimeS = getDouble("$key.t", 0.0),
        movingTimeS = getDouble("$key.mt", 0.0),
    )

    fun saveTrip(key: String, t: TripStats) {
        sp.edit()
            .putDouble("$key.km", t.distanceKm)
            .putDouble("$key.l", t.fuelL)
            .putDouble("$key.t", t.engineTimeS)
            .putDouble("$key.mt", t.movingTimeS)
            .apply()
    }

    private fun getDouble(key: String, def: Double): Double =
        if (sp.contains(key)) Double.fromBits(sp.getLong(key, 0)) else def

    private fun SharedPreferences.Editor.putDouble(key: String, v: Double) = putLong(key, v.toRawBits())

    private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
        runCatching { enumValueOf<T>(name!!) }.getOrDefault(def)
}

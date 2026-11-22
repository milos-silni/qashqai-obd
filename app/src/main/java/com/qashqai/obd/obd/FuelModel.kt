package com.qashqai.obd.obd

enum class FuelMode { AUTO, FUEL_RATE_PID, LOAD_MODEL, MAF_MODEL }
enum class FuelSource(val label: String) {
    FUEL_RATE_PID("ECU fuel rate (PID 5E)"),
    LOAD_MODEL("Load × RPM model"),
    MAF_MODEL("Air flow / AFR model"),
    NONE("Not available"),
}

data class FuelParams(
    val calibration: Double = 1.0,
    /** Injected fuel per stroke at 100 % load. ~45 mg fits the 1.5 dCi 106 hp (240 Nm). */
    val maxMgPerStroke: Double = 45.0,
    /** Assumed air/fuel ratio for the MAF model; diesel runs 18:1 (full load) .. 60:1 (idle). */
    val mafAfr: Double = 30.0,
    val cylinders: Int = 4,
)

/**
 * Diesel fuel consumption estimation.
 *
 * A 2009 K9K ECU almost certainly does not report PID 5E (fuel rate), and a diesel has no
 * fixed air/fuel ratio, so the plain "MAF / 14.7" trick petrol apps use is wrong here.
 * Default strategy: the ECU's calculated load tracks injected quantity on these engines,
 * so fuel/stroke = maxMgPerStroke × load, × injections per second. Fuel cut on overrun
 * (load 0 %) gives 0 L/h, as it should. A user calibration factor from real fill-ups fixes the scale.
 */
object FuelModel {
    const val DIESEL_DENSITY_G_PER_L = 832.0

    fun pickSource(mode: FuelMode, supported: Set<Int>): FuelSource {
        val hasRate = Pid.FUEL_RATE.code in supported
        val hasLoad = Pid.ENGINE_LOAD.code in supported && Pid.RPM.code in supported
        val hasMaf = Pid.MAF.code in supported
        return when (mode) {
            FuelMode.AUTO -> when {
                hasRate -> FuelSource.FUEL_RATE_PID
                hasLoad -> FuelSource.LOAD_MODEL
                hasMaf -> FuelSource.MAF_MODEL
                else -> FuelSource.NONE
            }
            FuelMode.FUEL_RATE_PID -> if (hasRate) FuelSource.FUEL_RATE_PID else FuelSource.NONE
            FuelMode.LOAD_MODEL -> if (hasLoad) FuelSource.LOAD_MODEL else FuelSource.NONE
            FuelMode.MAF_MODEL -> if (hasMaf) FuelSource.MAF_MODEL else FuelSource.NONE
        }
    }

    /** Litres per hour, or null if the needed values are missing. Engine off (rpm < 300) = 0. */
    fun litersPerHour(source: FuelSource, values: Map<Pid, Double>, p: FuelParams): Double? {
        val rpm = values[Pid.RPM]
        if (rpm != null && rpm < 300) return 0.0
        val raw = when (source) {
            FuelSource.FUEL_RATE_PID -> values[Pid.FUEL_RATE]
            FuelSource.LOAD_MODEL -> {
                val load = values[Pid.ENGINE_LOAD]
                if (rpm == null || load == null) null else {
                    val injectionsPerSec = rpm / 120.0 * p.cylinders   // 4-stroke: each cylinder fires every 2 revs
                    val gramsPerSec = p.maxMgPerStroke * (load / 100.0) * injectionsPerSec / 1000.0
                    gramsPerSec * 3600.0 / DIESEL_DENSITY_G_PER_L
                }
            }
            FuelSource.MAF_MODEL -> values[Pid.MAF]?.let { it / p.mafAfr * 3600.0 / DIESEL_DENSITY_G_PER_L }
            FuelSource.NONE -> null
        }
        return raw?.times(p.calibration)
    }

    /** L/100 km, only meaningful when moving. */
    fun litersPer100km(lph: Double?, speedKmh: Double?): Double? =
        if (lph == null || speedKmh == null || speedKmh < 5.0) null else lph / speedKmh * 100.0
}

/** Accumulated distance/fuel/time; immutable so it can live in UI state directly. */
data class TripStats(
    val distanceKm: Double = 0.0,
    val fuelL: Double = 0.0,
    val engineTimeS: Double = 0.0,
    val movingTimeS: Double = 0.0,
) {
    fun add(dtS: Double, speedKmh: Double, lph: Double, engineRunning: Boolean): TripStats {
        if (!engineRunning) return this
        return copy(
            distanceKm = distanceKm + speedKmh * dtS / 3600.0,
            fuelL = fuelL + lph * dtS / 3600.0,
            engineTimeS = engineTimeS + dtS,
            movingTimeS = movingTimeS + if (speedKmh >= 1) dtS else 0.0,
        )
    }

    val avgL100: Double? get() = if (distanceKm >= 0.5) fuelL / distanceKm * 100.0 else null
    val avgSpeedKmh: Double? get() = if (movingTimeS >= 10) distanceKm / (movingTimeS / 3600.0) else null
}

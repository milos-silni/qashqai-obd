package com.qashqai.obd.obd

/** Standard SAE J1979 mode-01 PIDs that a 2009 Qashqai 1.5 dCi (K9K, EOBD) may report. */
enum class Pid(val code: Int, val label: String, val unit: String, val bytes: Int, private val f: (IntArray) -> Double) {
    ENGINE_LOAD(0x04, "Engine load", "%", 1, { it[0] * 100.0 / 255 }),
    COOLANT_TEMP(0x05, "Coolant", "°C", 1, { it[0] - 40.0 }),
    INTAKE_MAP(0x0B, "Intake pressure", "kPa", 1, { it[0].toDouble() }),
    RPM(0x0C, "RPM", "rpm", 2, { (it[0] * 256 + it[1]) / 4.0 }),
    SPEED(0x0D, "Speed", "km/h", 1, { it[0].toDouble() }),
    INTAKE_TEMP(0x0F, "Intake air", "°C", 1, { it[0] - 40.0 }),
    MAF(0x10, "Air flow", "g/s", 2, { (it[0] * 256 + it[1]) / 100.0 }),
    THROTTLE(0x11, "Throttle", "%", 1, { it[0] * 100.0 / 255 }),
    RUN_TIME(0x1F, "Run time", "s", 2, { (it[0] * 256 + it[1]).toDouble() }),
    FUEL_RAIL_PRESSURE(0x23, "Rail pressure", "bar", 2, { (it[0] * 256 + it[1]) / 10.0 }),
    EGR_COMMANDED(0x2C, "EGR", "%", 1, { it[0] * 100.0 / 255 }),
    FUEL_LEVEL(0x2F, "Fuel level", "%", 1, { it[0] * 100.0 / 255 }),
    BARO(0x33, "Barometric", "kPa", 1, { it[0].toDouble() }),
    MODULE_VOLTAGE(0x42, "ECU voltage", "V", 2, { (it[0] * 256 + it[1]) / 1000.0 }),
    AMBIENT_TEMP(0x46, "Outside", "°C", 1, { it[0] - 40.0 }),
    ACCEL_PEDAL(0x49, "Pedal", "%", 1, { it[0] * 100.0 / 255 }),
    OIL_TEMP(0x5C, "Oil temp", "°C", 1, { it[0] - 40.0 }),
    FUEL_RATE(0x5E, "Fuel rate", "L/h", 2, { (it[0] * 256 + it[1]) / 20.0 });

    fun decode(data: IntArray): Double? = if (data.size >= bytes) f(data) else null

    companion object {
        /** Polled every cycle – needed for fuel and trip integration. */
        val FAST = listOf(RPM, SPEED, ENGINE_LOAD, MAF, FUEL_RATE)
        /** Polled round-robin, one per cycle. */
        val SLOW = listOf(
            COOLANT_TEMP, INTAKE_MAP, INTAKE_TEMP, FUEL_RAIL_PRESSURE, BARO, MODULE_VOLTAGE,
            AMBIENT_TEMP, OIL_TEMP, ACCEL_PEDAL, THROTTLE, EGR_COMMANDED, FUEL_LEVEL,
        )
        fun byCode(code: Int) = entries.firstOrNull { it.code == code }
    }
}

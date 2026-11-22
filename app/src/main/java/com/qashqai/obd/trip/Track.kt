package com.qashqai.obd.trip

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One GPS fix of a recorded trip.
 * [fuelL] is the fuel used since the trip started (from the OBD fuel model), so the
 * consumption of any stretch of road is (fuel at end − fuel at start) / distance.
 * [obd] is false when the car wasn't connected at that moment (no fuel data).
 */
data class TrackPoint(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Float,
    val fuelL: Double,
    val obd: Boolean,
) {
    fun toCsv(): String =
        String.format(Locale.US, "%d,%.6f,%.6f,%.1f,%.4f,%d", t, lat, lon, speedKmh, fuelL, if (obd) 1 else 0)

    companion object {
        fun parse(line: String): TrackPoint? = runCatching {
            val p = line.trim().split(',')
            TrackPoint(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toFloat(), p[4].toDouble(), p[5] == "1")
        }.getOrNull()
    }
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun distanceM(a: TrackPoint, b: TrackPoint) = distanceM(a.lat, a.lon, b.lat, b.lon)
}

data class TripSummary(
    /** Trip id = start time in epoch millis. */
    val id: Long,
    val endT: Long,
    val distanceKm: Double,
    val fuelL: Double,
    val hasFuelData: Boolean,
) {
    val durationMs: Long get() = endT - id
    val avgL100: Double? get() = if (hasFuelData && distanceKm >= 0.5) fuelL / distanceKm * 100.0 else null

    companion object {
        fun of(id: Long, points: List<TrackPoint>, now: Long? = null): TripSummary {
            var dist = 0.0
            for (i in 1 until points.size) dist += Geo.distanceM(points[i - 1], points[i])
            return TripSummary(
                id = id,
                endT = now ?: points.lastOrNull()?.t ?: id,
                distanceKm = dist / 1000.0,
                fuelL = points.lastOrNull()?.fuelL ?: 0.0,
                hasFuelData = points.any { it.obd },
            )
        }
    }
}

/** A run of consecutive points drawn in one colour. */
data class RouteLine(val points: List<TrackPoint>, val bucket: Int)

/**
 * Colours the route by fuel consumption: the track is cut into stretches of at least
 * [build]'s minLenM metres, each stretch gets its own L/100km, and neighbours with
 * the same colour are merged into one line.
 */
object RouteColors {
    const val NO_DATA = -1
    const val BUCKETS = 5

    /** green → light green → yellow → orange → red */
    private val palette = intArrayOf(
        0xFF00C853.toInt(), 0xFF76D72B.toInt(), 0xFFFFD600.toInt(), 0xFFFF6D00.toInt(), 0xFFE5281F.toInt(),
    )
    private const val GREY = 0xFF9E9E9E.toInt()

    fun color(bucket: Int): Int = if (bucket in palette.indices) palette[bucket] else GREY

    fun bucket(l100: Double?, green: Double, red: Double): Int {
        if (l100 == null || l100.isNaN()) return NO_DATA
        if (red <= green) return if (l100 <= green) 0 else BUCKETS - 1
        val t = ((l100 - green) / (red - green)).coerceIn(0.0, 1.0)
        return (t * (BUCKETS - 1)).roundToInt()
    }

    /** L/100km value at the centre of each bucket, for the legend. */
    fun bucketValue(bucket: Int, green: Double, red: Double): Double =
        green + bucket * (red - green) / (BUCKETS - 1)

    fun build(points: List<TrackPoint>, green: Double, red: Double, minLenM: Double = 150.0): List<RouteLine> {
        if (points.size < 2) return emptyList()
        val lines = ArrayList<RouteLine>()
        var current = ArrayList<TrackPoint>()
        var currentBucket = Int.MIN_VALUE
        var start = 0
        var dist = 0.0

        fun emit(end: Int) {
            val stretch = points.subList(start, end + 1)
            val withFuel = stretch.drop(1).all { it.obd }
            val l100 = if (withFuel && dist >= 1.0) (stretch.last().fuelL - stretch.first().fuelL) / (dist / 1000.0) * 100.0 else null
            val b = bucket(l100, green, red)
            if (b == currentBucket) {
                current.addAll(stretch.subList(1, stretch.size))
            } else {
                if (current.isNotEmpty()) lines += RouteLine(current, currentBucket)
                current = ArrayList(stretch)   // shares its first point with the previous line, so there are no gaps
                currentBucket = b
            }
            start = end
            dist = 0.0
        }

        for (i in 1 until points.size) {
            dist += Geo.distanceM(points[i - 1], points[i])
            if (dist >= minLenM) emit(i)
        }
        if (start < points.size - 1) emit(points.size - 1)
        if (current.isNotEmpty()) lines += RouteLine(current, currentBucket)
        return lines
    }
}

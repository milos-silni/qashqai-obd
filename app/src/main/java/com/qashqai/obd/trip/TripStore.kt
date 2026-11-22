package com.qashqai.obd.trip

import android.content.Context
import java.io.File

/** Trips are plain CSV files (one GPS fix per line) in the app's private storage. */
class TripStore(context: Context) {
    private val dir = File(context.filesDir, "trips").apply { mkdirs() }

    private fun file(id: Long) = File(dir, "trip_$id.csv")

    fun append(id: Long, p: TrackPoint) = file(id).appendText(p.toCsv() + "\n")

    fun load(id: Long): List<TrackPoint> {
        val f = file(id)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull(TrackPoint::parse)
    }

    fun list(): List<TripSummary> =
        dir.listFiles { f -> f.name.startsWith("trip_") && f.name.endsWith(".csv") }.orEmpty()
            .mapNotNull { f ->
                val id = f.name.removePrefix("trip_").removeSuffix(".csv").toLongOrNull() ?: return@mapNotNull null
                val pts = load(id)
                if (pts.size < 2) null else TripSummary.of(id, pts)
            }
            .sortedByDescending { it.id }

    fun delete(id: Long) = file(id).delete()
}

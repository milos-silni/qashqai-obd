package com.qashqai.obd.ui

import android.annotation.SuppressLint
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.qashqai.obd.data.AppSettings
import com.qashqai.obd.trip.RecordingState
import com.qashqai.obd.trip.RouteColors
import com.qashqai.obd.trip.RouteLine
import com.qashqai.obd.trip.TrackPoint
import com.qashqai.obd.trip.TripRecorder
import com.qashqai.obd.trip.TripSummary
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MapScreen(
    rec: RecordingState,
    trips: List<TripSummary>,
    settings: AppSettings,
    recorder: TripRecorder,
    requestPermissions: () -> Unit,
) {
    var selected by remember { mutableStateOf<TripSummary?>(null) }
    var selectedPoints by remember { mutableStateOf<List<TrackPoint>>(emptyList()) }
    var follow by remember { mutableStateOf(true) }
    var toDelete by remember { mutableStateOf<TripSummary?>(null) }

    LaunchedEffect(selected?.id) {
        selectedPoints = emptyList()
        selected?.let { selectedPoints = recorder.load(it.id) }
    }

    val live = selected == null
    val points = if (live) rec.points else selectedPoints
    val lines = remember(points.size, selected?.id, settings.greenBelow, settings.redAbove) {
        RouteColors.build(points, settings.greenBelow, settings.redAbove)
    }

    Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Palette.card)) {
            OsmMap(
                lines = lines,
                current = if (live) points.lastOrNull() else null,
                fitKey = selected?.id?.takeIf { selectedPoints.isNotEmpty() },
                follow = live && follow,
                dark = settings.darkMap,
                initialCenter = remember { recorder.lastKnownLocation() },
                onUserPan = { follow = false },
                modifier = Modifier.fillMaxSize(),
            )
            Legend(settings, Modifier.align(Alignment.TopStart).padding(8.dp))
            if (live && !follow) {
                Button(onClick = { follow = true }, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) { Text("Follow car") }
            }
        }

        Column(
            Modifier.width(310.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Palette.card).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val sel = selected
            if (sel != null) {
                Text("SAVED TRIP", color = Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(dateFmt.format(Date(sel.id)), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                TripStats(sel, settings)
                OutlinedButton(onClick = { selected = null }) { Text("Back to live") }
            } else {
                LiveControls(rec, settings, recorder, requestPermissions)
            }
            HorizontalDivider(color = Palette.cardHi)
            Text("SAVED TRIPS", color = Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (trips.isEmpty()) Text("No trips yet", color = Palette.dim, fontSize = 14.sp)
            LazyColumn(Modifier.weight(1f)) {
                items(trips, key = { it.id }) { t ->
                    TripRow(t, settings, selected = t.id == sel?.id, onClick = { selected = t }, onDelete = { toDelete = t })
                }
            }
        }
    }

    toDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete trip?") },
            text = { Text("${dateFmt.format(Date(t.id))}, ${t.distanceKm.fmt(1)} km. This can't be undone.") },
            confirmButton = {
                Button(onClick = {
                    recorder.delete(t.id)
                    if (selected?.id == t.id) selected = null
                    toDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

private val dateFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

@Composable
private fun LiveControls(rec: RecordingState, settings: AppSettings, recorder: TripRecorder, requestPermissions: () -> Unit) {
    if (rec.recording) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(Palette.bad))
            Spacer(Modifier.width(8.dp))
            Text("RECORDING", color = Palette.bad, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(if (rec.gpsFix) "GPS ok" else "Waiting for GPS…", color = if (rec.gpsFix) Palette.good else Palette.warn, fontSize = 13.sp)
        }
        TripStats(TripSummary.of(rec.tripId ?: 0L, rec.points, now = System.currentTimeMillis()), settings)
        Button(
            onClick = { recorder.stop() },
            colors = ButtonDefaults.buttonColors(containerColor = Palette.bad, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Stop & save trip") }
    } else {
        Text("LIVE", color = Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Button(
            onClick = { if (recorder.hasLocationPermission()) recorder.start() else requestPermissions() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start recording trip") }
        Text(
            if (settings.autoRecord) "Trips also record by themselves while the car is connected." else "Auto-record is off (Setup).",
            color = Palette.dim, fontSize = 13.sp,
        )
    }
    rec.message?.let { Text(it, color = Palette.accent, fontSize = 14.sp) }
}

@Composable
private fun TripStats(s: TripSummary, settings: AppSettings) {
    val avg = s.avgL100
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            MiniStat("Distance", s.distanceKm.fmt(1), "km", Modifier.weight(1f))
            MiniStat("Time", formatDuration(s.durationMs / 1000.0), "h:mm", Modifier.weight(1f))
        }
        Row {
            MiniStat("Average", avg.fmt(1), "L/100km", Modifier.weight(1f),
                color = avg?.let { Color(RouteColors.color(RouteColors.bucket(it, settings.greenBelow, settings.redAbove))) } ?: Palette.text)
            MiniStat("Fuel", if (s.hasFuelData) s.fuelL.fmt(2) else "—", "L", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, unit: String, modifier: Modifier, color: Color = Palette.text) {
    Column(modifier) {
        Text(label, color = Palette.dim, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = color)
            Spacer(Modifier.width(4.dp))
            Text(unit, fontSize = 11.sp, color = Palette.dim, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun TripRow(t: TripSummary, settings: AppSettings, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val avg = t.avgL100
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) Palette.cardHi else Color.Transparent)
            .clickable(onClick = onClick).padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(RouteColors.color(RouteColors.bucket(avg, settings.greenBelow, settings.redAbove)))))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(dateFmt.format(Date(t.id)), fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Text(
                "${t.distanceKm.fmt(1)} km · ${formatDuration(t.durationMs / 1000.0)} · ${avg?.let { "${it.fmt(1)} L/100" } ?: "no fuel data"}",
                fontSize = 13.sp, color = Palette.dim,
            )
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete trip", tint = Palette.dim) }
    }
}

@Composable
private fun Legend(settings: AppSettings, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("L/100km", color = Color.White, fontSize = 12.sp)
        for (b in 0 until RouteColors.BUCKETS) {
            Box(Modifier.size(width = 18.dp, height = 8.dp).clip(RoundedCornerShape(4.dp)).background(Color(RouteColors.color(b))))
            val v = RouteColors.bucketValue(b, settings.greenBelow, settings.redAbove).fmt(if (b == 0 || b == RouteColors.BUCKETS - 1) 0 else 1)
            Text(
                when (b) { 0 -> "≤$v"; RouteColors.BUCKETS - 1 -> "≥$v"; else -> v },
                color = Color.White, fontSize = 12.sp,
            )
        }
        Box(Modifier.size(width = 18.dp, height = 8.dp).clip(RoundedCornerShape(4.dp)).background(Color(RouteColors.color(RouteColors.NO_DATA))))
        Text("no car data", color = Color.White, fontSize = 12.sp)
    }
}

// ---- osmdroid map ---------------------------------------------------------------

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun OsmMap(
    lines: List<RouteLine>,
    current: TrackPoint?,
    fitKey: Long?,
    follow: Boolean,
    dark: Boolean,
    initialCenter: Pair<Double, Double>?,
    onUserPan: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val onPan by rememberUpdatedState(onUserPan)
    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.ALWAYS)
            isTilesScaledToDpi = true   // readable street names on low-dpi head units
            if (initialCenter != null) {
                controller.setZoom(15.0)
                controller.setCenter(GeoPoint(initialCenter.first, initialCenter.second))
            } else {
                controller.setZoom(5.0)
                controller.setCenter(GeoPoint(45.0, 15.0))
            }
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_MOVE) onPan()
                false
            }
        }
    }
    val overlays = remember { RouteOverlays(map) }

    DisposableEffect(Unit) {
        map.onResume()
        onDispose {
            map.onPause()
            map.onDetach()
        }
    }

    AndroidView(factory = { map }, modifier = modifier, update = { overlays.update(lines, current, dark, follow) })

    LaunchedEffect(fitKey) {
        if (fitKey != null) map.post { overlays.fitToRoute() }
    }
}

/**
 * Night map: grey-scale and inverted (roads light on dark), so it doesn't dazzle and the
 * coloured route is the only colour on screen.
 */
private val DARK_GREY_TILES = run {
    val k = 0.8f            // contrast
    val r = -0.299f * k; val g = -0.587f * k; val b = -0.114f * k
    ColorMatrixColorFilter(floatArrayOf(
        r, g, b, 0f, 225f,
        r, g, b, 0f, 225f,
        r, g, b, 0f, 232f,  // a hint of blue
        0f, 0f, 0f, 1f, 0f,
    ))
}

/** Keeps the polylines and the car marker on the map in sync with the route. */
private class RouteOverlays(private val map: MapView) {
    private var polylines: List<Polyline> = emptyList()
    private var rendered: List<RouteLine>? = null
    private var routePoints: List<GeoPoint> = emptyList()
    private var darkApplied: Boolean? = null
    private var zoomedToFirstFix = false

    private val marker = Marker(map).apply {
        icon = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF2196F3.toInt())
            setStroke(5, 0xFFFFFFFF.toInt())
            setSize(40, 40)
        }
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        infoWindow = null
        setOnMarkerClickListener { _, _ -> true }
    }

    fun update(lines: List<RouteLine>, current: TrackPoint?, dark: Boolean, follow: Boolean) {
        if (dark != darkApplied) {
            map.overlayManager.tilesOverlay.setColorFilter(if (dark) DARK_GREY_TILES else null)
            darkApplied = dark
        }
        if (lines !== rendered) {
            map.overlays.removeAll(polylines)
            polylines = lines.map { line ->
                Polyline(map).apply {
                    setPoints(line.points.map { GeoPoint(it.lat, it.lon) })
                    outlinePaint.color = RouteColors.color(line.bucket)
                    outlinePaint.strokeWidth = 14f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                    infoWindow = null
                }
            }
            routePoints = lines.flatMap { l -> l.points.map { GeoPoint(it.lat, it.lon) } }
            map.overlays.addAll(0, polylines)
            rendered = lines
        }
        map.overlays.remove(marker)
        if (current != null) {
            val p = GeoPoint(current.lat, current.lon)
            marker.position = p
            map.overlays.add(marker)   // always on top of the route
            if (!zoomedToFirstFix) {
                map.controller.setZoom(16.0)
                map.controller.setCenter(p)
                zoomedToFirstFix = true
            } else if (follow) {
                map.controller.animateTo(p)
            }
        }
        map.invalidate()
    }

    fun fitToRoute() {
        if (routePoints.size >= 2) map.zoomToBoundingBox(BoundingBox.fromGeoPoints(routePoints), false, 80)
    }
}

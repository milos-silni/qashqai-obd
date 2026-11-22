package com.qashqai.obd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qashqai.obd.data.AppSettings
import com.qashqai.obd.data.ConnState
import com.qashqai.obd.data.DashState
import com.qashqai.obd.data.ObdManager
import com.qashqai.obd.obd.Pid

@Composable
fun DashboardScreen(state: DashState, settings: AppSettings, obd: ObdManager, recording: Boolean, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusBar(state, settings, obd, recording, onOpenSettings)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FuelPanel(state, obd, Modifier.weight(1f).fillMaxHeight())
            GaugeGrid(state, Modifier.weight(1.55f).fillMaxHeight())
        }
    }
}

@Composable
private fun StatusBar(state: DashState, settings: AppSettings, obd: ObdManager, recording: Boolean, onOpenSettings: () -> Unit) {
    val dot = when (state.conn) {
        ConnState.CONNECTED -> Palette.good
        ConnState.CONNECTING, ConnState.INITIALIZING -> Palette.warn
        ConnState.ERROR -> Palette.bad
        ConnState.DISCONNECTED -> Palette.dim
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.card).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Text(state.status, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (recording) {
            Text("● REC", color = Palette.bad, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
        }
        val info = listOfNotNull(state.adapterVersion, state.protocol).joinToString("  ·  ")
        if (info.isNotEmpty() && state.conn == ConnState.CONNECTED) {
            Text(info, fontSize = 13.sp, color = Palette.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(12.dp))
        }
        when {
            !obd.isConfigured(settings) -> Button(onClick = onOpenSettings) { Text("Choose adapter") }
            state.sessionActive -> OutlinedButton(onClick = { obd.disconnect() }) { Text("Disconnect") }
            else -> Button(onClick = { obd.connect() }) { Text("Connect") }
        }
    }
}

@Composable
private fun FuelPanel(state: DashState, obd: ObdManager, modifier: Modifier) {
    var confirmReset by remember { mutableStateOf(false) }
    val l100 = state.instantL100
    val moving = l100 != null

    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Palette.card).padding(16.dp)) {
        Text("INSTANT CONSUMPTION", color = Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (moving) l100.fmt(1) else state.instantLph.fmt(1),
                fontSize = 76.sp, fontWeight = FontWeight.Bold, color = consumptionColor(l100), lineHeight = 80.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(if (moving) "L/100km" else "L/h", fontSize = 20.sp, color = Palette.dim, modifier = Modifier.padding(bottom = 14.dp))
        }
        ConsumptionBar(if (moving) l100 else null)

        Spacer(Modifier.weight(1f))
        Text("TRIP", color = Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Row {
            Stat("Average", state.trip.avgL100.fmt(1), "L/100km", Modifier.weight(1f), big = true)
            Stat("Fuel used", state.trip.fuelL.fmt(2), "L", Modifier.weight(1f), big = true)
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Stat("Distance", state.trip.distanceKm.fmt(1), "km", Modifier.weight(1f))
            Stat("Avg speed", state.trip.avgSpeedKmh.fmt(0), "km/h", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stat("Engine time", formatDuration(state.trip.engineTimeS), "h:mm", Modifier.weight(1f))
            TextButton(onClick = { confirmReset = true }) { Text("Reset trip") }
        }
        Text(
            "${state.fuelSource.label} · cal ×${obd.settings.value.fuel.calibration.fmt(2)}",
            color = Palette.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset trip?") },
            text = { Text("Distance, fuel used and average will start from zero.") },
            confirmButton = { Button(onClick = { obd.resetTrip(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

private fun consumptionColor(l100: Double?): Color = when {
    l100 == null -> Palette.text
    l100 < 5 -> Palette.good
    l100 < 8 -> Palette.text
    l100 < 12 -> Palette.warn
    else -> Palette.bad
}

@Composable
private fun ConsumptionBar(l100: Double?, max: Double = 20.0) {
    val frac = ((l100 ?: 0.0) / max).coerceIn(0.0, 1.0).toFloat()
    Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Palette.cardHi)) {
        if (frac > 0f) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(7.dp)).background(consumptionColor(l100)))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text("0", fontSize = 11.sp, color = Palette.dim)
        Spacer(Modifier.weight(1f))
        Text("10", fontSize = 11.sp, color = Palette.dim)
        Spacer(Modifier.weight(1f))
        Text("20", fontSize = 11.sp, color = Palette.dim)
    }
}

@Composable
private fun Stat(label: String, value: String, unit: String, modifier: Modifier = Modifier, big: Boolean = false) {
    Column(modifier) {
        Text(label, color = Palette.dim, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = if (big) 30.sp else 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            Text(unit, fontSize = 12.sp, color = Palette.dim, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

private data class TileData(val label: String, val value: String, val unit: String, val color: Color = Palette.text)

@Composable
private fun GaugeGrid(state: DashState, modifier: Modifier) {
    val v = state.values
    val map = v[Pid.INTAKE_MAP]
    val baro = v[Pid.BARO] ?: 101.3
    val boostBar = map?.let { (it - baro) / 100.0 }
    val coolant = v[Pid.COOLANT_TEMP]
    val battery = state.batteryV ?: v[Pid.MODULE_VOLTAGE]

    val tiles = listOf(
        TileData("Speed", v[Pid.SPEED].fmt(0), "km/h"),
        TileData("RPM", v[Pid.RPM].fmt(0), "rpm", if ((v[Pid.RPM] ?: 0.0) > 4200) Palette.bad else Palette.text),
        TileData("Coolant", coolant.fmt(0), "°C", when {
            coolant == null -> Palette.text
            coolant < 60 -> Palette.cold
            coolant > 105 -> Palette.bad
            else -> Palette.good
        }),
        TileData("Boost", boostBar.fmt(2), "bar", Palette.text),
        TileData("Engine load", v[Pid.ENGINE_LOAD].fmt(0), "%"),
        TileData("Battery", battery.fmt(1), "V", when {
            battery == null -> Palette.text
            battery < 12.0 -> Palette.bad
            battery < 13.2 && (v[Pid.RPM] ?: 0.0) > 500 -> Palette.warn // not charging while running
            else -> Palette.text
        }),
        TileData("Intake air", v[Pid.INTAKE_TEMP].fmt(0), "°C"),
        if (Pid.FUEL_RAIL_PRESSURE in v || Pid.FUEL_RAIL_PRESSURE.code in state.supported)
            TileData("Rail pressure", v[Pid.FUEL_RAIL_PRESSURE].fmt(0), "bar")
        else TileData("Air flow", v[Pid.MAF].fmt(1), "g/s"),
        when {
            Pid.OIL_TEMP.code in state.supported -> TileData("Oil", v[Pid.OIL_TEMP].fmt(0), "°C")
            Pid.AMBIENT_TEMP.code in state.supported -> TileData("Outside", v[Pid.AMBIENT_TEMP].fmt(0), "°C")
            else -> TileData("Fuel rate", state.instantLph.fmt(2), "L/h")
        },
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(3).forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { Tile(it, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

@Composable
private fun Tile(t: TileData, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Palette.card).padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(t.label.uppercase(), color = Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            Text(t.value, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = t.color, maxLines = 1, lineHeight = 46.sp)
            Spacer(Modifier.width(6.dp))
            Text(t.unit, fontSize = 15.sp, color = Palette.dim, modifier = Modifier.padding(bottom = 7.dp))
        }
    }
}

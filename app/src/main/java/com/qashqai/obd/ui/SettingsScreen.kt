package com.qashqai.obd.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qashqai.obd.data.AppSettings
import com.qashqai.obd.data.DashState
import com.qashqai.obd.data.ObdManager
import com.qashqai.obd.obd.FuelMode

@Composable
fun SettingsScreen(
    state: DashState,
    settings: AppSettings,
    obd: ObdManager,
    hasBluetoothPermission: () -> Boolean,
    requestBluetoothPermission: () -> Unit,
) {
    Row(
        Modifier.fillMaxSize().padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ConnectionCard(settings, obd, hasBluetoothPermission, requestBluetoothPermission)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TripMapCard(settings, obd)
            CalibrationCard(state, settings, obd)
            FuelModelCard(settings, obd)
        }
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, fontSize = 16.sp)
    }
}

@Composable
private fun ConnectionCard(
    settings: AppSettings,
    obd: ObdManager,
    hasBluetoothPermission: () -> Boolean,
    requestBluetoothPermission: () -> Unit,
) {
    val context = LocalContext.current
    var showDevices by remember { mutableStateOf(false) }

    Section("Bluetooth OBD adapter (ELM327)") {
        Text(
            settings.btAddress?.let { "Selected: ${settings.btName ?: "?"} ($it)" }
                ?: "Pair the adapter in Android Bluetooth settings first (PIN is usually 1234 or 0000), then choose it here.",
            color = Palette.dim, fontSize = 14.sp,
        )
        Button(onClick = {
            if (hasBluetoothPermission()) showDevices = true else requestBluetoothPermission()
        }) { Text("Choose paired adapter") }

        Spacer(Modifier.padding(2.dp))
        Text("Protocol", fontWeight = FontWeight.SemiBold)
        RadioRow("Automatic", settings.protocol == "0") { obd.updateSettings { it.copy(protocol = "0") } }
        RadioRow("CAN 11-bit 500k (Qashqai J10, faster connect)", settings.protocol == "6") { obd.updateSettings { it.copy(protocol = "6") } }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-connect & reconnect", fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(checked = settings.autoConnect, onCheckedChange = { v -> obd.updateSettings { it.copy(autoConnect = v) } })
        }
        Button(onClick = { obd.reconnect() }, enabled = obd.isConfigured(settings)) { Text("Connect now") }
    }

    if (showDevices) {
        val devices = remember { bondedDevices(context) }
        AlertDialog(
            onDismissRequest = { showDevices = false },
            title = { Text("Paired Bluetooth devices") },
            text = {
                if (devices.isEmpty()) {
                    Text("No paired devices. Pair the OBD adapter in Android Bluetooth settings (often named OBDII, V-LINK or ELM327).")
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(devices) { (name, address) ->
                            val likely = listOf("OBD", "ELM", "LINK", "VGATE", "ICAR", "KONNWEI").any { name.uppercase().contains(it) }
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    obd.updateSettings { it.copy(btAddress = address, btName = name) }
                                    showDevices = false
                                    obd.reconnect()
                                }.padding(vertical = 10.dp),
                            ) {
                                Text(name, fontSize = 17.sp, fontWeight = if (likely) FontWeight.Bold else FontWeight.Normal,
                                    color = if (likely) Palette.accent else Palette.text)
                                Text(address, fontSize = 13.sp, color = Palette.dim)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDevices = false }) { Text("Close") } },
        )
    }
}

@SuppressLint("MissingPermission")
private fun bondedDevices(context: Context): List<Pair<String, String>> = runCatching {
    val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bm.adapter?.bondedDevices.orEmpty().map { (it.name ?: "Unknown") to it.address }.sortedBy { it.first }
}.getOrDefault(emptyList())

@Composable
private fun TripMapCard(settings: AppSettings, obd: ObdManager) {
    var green by remember(settings.greenBelow) { mutableStateOf(settings.greenBelow.fmt(1)) }
    var red by remember(settings.redAbove) { mutableStateOf(settings.redAbove.fmt(1)) }

    Section("Trip map") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Record trips automatically", fontSize = 16.sp)
                Text("Starts when the car connects, saves 2 min after the engine is off", color = Palette.dim, fontSize = 13.sp)
            }
            Switch(checked = settings.autoRecord, onCheckedChange = { v -> obd.updateSettings { it.copy(autoRecord = v) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dark map", fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(checked = settings.darkMap, onCheckedChange = { v -> obd.updateSettings { it.copy(darkMap = v) } })
        }
        Text("Route colours (L/100km)", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                green, { green = it.replace(',', '.') }, label = { Text("Green up to") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                red, { red = it.replace(',', '.') }, label = { Text("Red from") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = {
                val g = green.toDoubleOrNull()?.coerceIn(1.0, 30.0) ?: settings.greenBelow
                val r = (red.toDoubleOrNull()?.coerceIn(1.0, 40.0) ?: settings.redAbove).coerceAtLeast(g + 0.5)
                obd.updateSettings { it.copy(greenBelow = g, redAbove = r) }
            }) { Text("Save") }
        }
    }
}

@Composable
private fun CalibrationCard(state: DashState, settings: AppSettings, obd: ObdManager) {
    var liters by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmMark by remember { mutableStateOf(false) }

    Section("Fill-up calibration") {
        Text(
            "Makes the consumption match reality. 1) Fill the tank to the top and tap \"Tank is full\". " +
                "2) Drive normally (the more km, the better – at least 300). 3) Fill to the top again, enter the litres from the pump and tap Calibrate.",
            color = Palette.dim, fontSize = 14.sp,
        )
        Text(
            "Since last full tank: ${state.fill.distanceKm.fmt(1)} km, app measured ${state.fill.fuelL.fmt(2)} L" +
                (state.fill.avgL100?.let { " (${it.fmt(1)} L/100km)" } ?: ""),
            fontSize = 15.sp,
        )
        Text("Current factor: ×${settings.fuel.calibration.fmt(3)}", fontSize = 15.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                liters, { liters = it.replace(',', '.') }, label = { Text("Litres at pump") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                message = obd.calibrateFromFillUp(liters.toDoubleOrNull() ?: 0.0) ?: "Calibrated. New factor ×${obd.settings.value.fuel.calibration.fmt(3)}"
                liters = ""
            }) { Text("Calibrate") }
        }
        message?.let { Text(it, color = Palette.accent, fontSize = 14.sp) }
        Row {
            OutlinedButton(onClick = { confirmMark = true }) { Text("Tank is full (start)") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                obd.updateSettings { it.copy(fuel = it.fuel.copy(calibration = 1.0)) }
                message = "Factor reset to ×1.000"
            }) { Text("Reset factor") }
        }
    }

    if (confirmMark) {
        AlertDialog(
            onDismissRequest = { confirmMark = false },
            title = { Text("Tank is full?") },
            text = { Text("Starts a new measurement from zero for calibration.") },
            confirmButton = { Button(onClick = { obd.markFullTank(); confirmMark = false; message = "Measuring from now" }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = { confirmMark = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FuelModelCard(settings: AppSettings, obd: ObdManager) {
    var maxMg by remember(settings.fuel.maxMgPerStroke) { mutableStateOf(settings.fuel.maxMgPerStroke.fmt(1)) }
    var afr by remember(settings.fuel.mafAfr) { mutableStateOf(settings.fuel.mafAfr.fmt(1)) }

    Section("Fuel calculation (advanced)") {
        RadioRow("Automatic (best available)", settings.fuelMode == FuelMode.AUTO) { obd.updateSettings { it.copy(fuelMode = FuelMode.AUTO) } }
        RadioRow("ECU fuel rate PID 5E", settings.fuelMode == FuelMode.FUEL_RATE_PID) { obd.updateSettings { it.copy(fuelMode = FuelMode.FUEL_RATE_PID) } }
        RadioRow("Engine load × RPM", settings.fuelMode == FuelMode.LOAD_MODEL) { obd.updateSettings { it.copy(fuelMode = FuelMode.LOAD_MODEL) } }
        RadioRow("Air flow (MAF) ÷ AFR", settings.fuelMode == FuelMode.MAF_MODEL) { obd.updateSettings { it.copy(fuelMode = FuelMode.MAF_MODEL) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                maxMg, { maxMg = it.replace(',', '.') }, label = { Text("mg/stroke @100% load") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                afr, { afr = it.replace(',', '.') }, label = { Text("MAF model AFR") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
            )
        }
        OutlinedButton(onClick = {
            obd.updateSettings {
                it.copy(fuel = it.fuel.copy(
                    maxMgPerStroke = maxMg.toDoubleOrNull()?.coerceIn(10.0, 120.0) ?: it.fuel.maxMgPerStroke,
                    mafAfr = afr.toDoubleOrNull()?.coerceIn(14.0, 80.0) ?: it.fuel.mafAfr,
                ))
            }
        }) { Text("Save") }
        Text(
            "The 2009 1.5 dCi ECU usually doesn't report fuel rate, so the app estimates it. Use fill-up calibration instead of tuning these by hand.",
            color = Palette.dim, fontSize = 13.sp,
        )
    }
}

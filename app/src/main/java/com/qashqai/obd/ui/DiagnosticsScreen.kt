package com.qashqai.obd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qashqai.obd.data.ConnState
import com.qashqai.obd.data.DashState
import com.qashqai.obd.data.ObdManager
import com.qashqai.obd.obd.Pid
import java.util.Locale

/** Short explanations for codes commonly seen on the K9K 1.5 dCi. */
private val knownCodes = mapOf(
    "P0100" to "Air flow meter circuit",
    "P0101" to "Air flow meter range/performance",
    "P0115" to "Coolant temperature sensor",
    "P0190" to "Fuel rail pressure sensor",
    "P0200" to "Injector circuit",
    "P0234" to "Turbo overboost",
    "P0235" to "Boost pressure sensor",
    "P0299" to "Turbo underboost",
    "P0335" to "Crankshaft position sensor",
    "P0340" to "Camshaft position sensor",
    "P0380" to "Glow plug circuit",
    "P0400" to "EGR flow malfunction",
    "P0401" to "EGR insufficient flow (often sooted EGR valve)",
    "P0403" to "EGR control circuit",
    "P0560" to "System voltage",
    "P0571" to "Brake switch",
    "P0606" to "ECU processor fault",
    "P0670" to "Glow plug control module",
    "P2263" to "Turbo boost system performance",
)

@Composable
fun DiagnosticsScreen(state: DashState, obd: ObdManager) {
    var confirmClear by remember { mutableStateOf(false) }
    val connected = state.conn == ConnState.CONNECTED

    Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("Trouble codes (check engine)") {
                Row {
                    Button(onClick = { obd.readDtcs() }, enabled = connected) { Text("Read codes") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { confirmClear = true }, enabled = connected) { Text("Clear codes") }
                }
                state.dtcStatus?.let { Text(it, color = Palette.dim) }
                state.dtcs?.forEach { code ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.cardHi).padding(10.dp)) {
                        Text(code, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.accent)
                        Text(knownCodes[code] ?: "Look up this code for a Nissan/Renault K9K", color = Palette.dim, fontSize = 14.sp)
                    }
                }
            }
            FuelDataTools(obd, connected)
            Section("All live values") {
                if (state.values.isEmpty()) Text("No data yet", color = Palette.dim)
                Pid.entries.filter { it in state.values }.forEach { pid ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(pid.label, modifier = Modifier.weight(1f), color = Palette.dim)
                        Text("${state.values[pid].fmt(if (pid.unit == "V" || pid.unit == "g/s") 2 else 1)} ${pid.unit}")
                    }
                }
                if (state.supported.isNotEmpty()) {
                    Text(
                        "ECU supports PIDs: " + state.supported.sorted().joinToString(" ") { String.format(Locale.US, "%02X", it) },
                        fontSize = 12.sp, color = Palette.dim, fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Palette.card).padding(12.dp)) {
            Text("Connection log", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            val listState = rememberLazyListState()
            LaunchedEffect(state.log.size) { if (state.log.isNotEmpty()) listState.scrollToItem(state.log.size - 1) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.log) { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Palette.dim) }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear trouble codes?") },
            text = { Text("This erases stored codes and turns off the check-engine light. Do it with the engine OFF and ignition ON. If the fault is still there, the code will come back.") },
            confirmButton = { Button(onClick = { obd.clearDtcs(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** ECU scan + CAN capture: collects what's needed to read the real injected fuel quantity later. */
@Composable
private fun FuelDataTools(obd: ObdManager, connected: Boolean) {
    val scan by obd.scan.collectAsState()
    val context = LocalContext.current
    var viewing by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    val busy = scan.busy != null

    Section("Find fuel data (manufacturer)") {
        Text(
            "Park with the engine running. ECU scan asks the engine ECU what it can report (read-only, about 1–2 min). " +
                "CAN capture records the car's own messages: do one at idle, then one while holding about 2500 rpm.",
            color = Palette.dim, fontSize = 14.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { obd.scanEcu() }, enabled = connected && !busy) { Text("Scan ECU") }
            OutlinedButton(onClick = { obd.captureCan(30) }, enabled = connected && !busy) { Text("Capture CAN 30 s") }
        }
        if (busy) {
            val p = scan.progress
            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = Palette.accent)
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Palette.accent)
        }
        scan.status?.let { Text(it, color = Palette.dim) }
        if (busy) OutlinedButton(onClick = { obd.cancelScan() }) { Text("Stop") }
        scan.summary.forEach { Text("• $it", fontSize = 14.sp) }

        if (scan.reports.isNotEmpty()) {
            Text("Saved reports (${obd.reports.dir.absolutePath.substringAfter("/Android/", "").let { if (it.isEmpty()) "app storage" else "Android/$it" }})",
                color = Palette.dim, fontSize = 12.sp)
        }
        scan.reports.take(8).forEach { f ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.cardHi).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text("${(f.length() + 1023) / 1024} KB", fontSize = 11.sp, color = Palette.dim)
                }
                TextButton(onClick = { viewing = f }) { Text("View") }
                TextButton(onClick = {
                    try {
                        context.startActivity(obd.reports.shareIntent(f))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, "No app to share with – copy it over USB from ${f.parent}", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Share") }
                TextButton(onClick = { deleting = f }) { Text("Delete", color = Palette.dim) }
            }
        }
    }

    viewing?.let { f ->
        val text by produceState("Loading…", f) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val t = f.readText()
                    if (t.length > 60_000) t.take(60_000) + "\n… (${t.length / 1024} KB in total – share the file to see all of it)" else t
                }.getOrElse { "Can't read file: ${it.message}" }
            }
        }
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(f.name, fontSize = 16.sp) },
            text = {
                SelectionContainer(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("Close") } },
        )
    }

    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete report?") },
            text = { Text(f.name) },
            confirmButton = { Button(onClick = { obd.deleteReport(f); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

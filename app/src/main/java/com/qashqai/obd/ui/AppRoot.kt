package com.qashqai.obd.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.qashqai.obd.data.ObdManager
import com.qashqai.obd.trip.TripRecorder

@Composable
fun AppRoot(
    obd: ObdManager,
    trips: TripRecorder,
    hasBluetoothPermission: () -> Boolean,
    requestPermissions: () -> Unit,
) {
    val state by obd.state.collectAsState()
    val settings by obd.settings.collectAsState()
    val rec by trips.state.collectAsState()
    val savedTrips by trips.trips.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Surface(color = Palette.bg, contentColor = Palette.text, modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            NavigationRail(containerColor = Palette.card, modifier = Modifier.fillMaxHeight()) {
                NavigationRailItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Dash") })
                NavigationRailItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Place, null) }, label = { Text("Map") })
                NavigationRailItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Build, null) }, label = { Text("Diag") })
                NavigationRailItem(selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Setup") })
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    0 -> DashboardScreen(state, settings, obd, recording = rec.recording, onOpenSettings = { tab = 3 })
                    1 -> MapScreen(rec, savedTrips, settings, trips, requestPermissions)
                    2 -> DiagnosticsScreen(state, obd)
                    else -> SettingsScreen(state, settings, obd, hasBluetoothPermission, requestPermissions)
                }
            }
        }
    }
}

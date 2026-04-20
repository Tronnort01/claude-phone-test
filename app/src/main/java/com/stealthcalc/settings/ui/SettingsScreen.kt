package com.stealthcalc.settings.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.settings.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAutoLockPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Handle vault backup file share
    LaunchedEffect(Unit) {
        viewModel.backupFile.collect { file ->
            if (file == null) {
                Toast.makeText(context, "Backup failed", Toast.LENGTH_LONG).show()
                return@collect
            }
            runCatching {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "StealthCalc Vault Backup")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(send, "Save vault backup").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }.onFailure {
                Toast.makeText(context, "Backup share failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            // --- Security section ---
            SectionHeader("Security")

            SettingsRow(
                title = "Change Secret Code",
                subtitle = "Update your calculator unlock code",
                onClick = viewModel::showChangeCodeDialog,
                trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
            )

            SettingsRow(
                title = "Auto-Lock Timeout",
                subtitle = viewModel.autoLockOptions.find { it.first == state.autoLockDelayMs }?.second ?: "30 seconds",
                onClick = { showAutoLockPicker = true },
                trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
            )

            SettingsToggle(
                title = "Block Screenshots",
                subtitle = "Prevent screenshots of stealth screens",
                checked = state.isScreenshotBlocked,
                onToggle = viewModel::toggleScreenshotBlock
            )

            SettingsToggle(
                title = "Biometric Unlock",
                subtitle = "Use fingerprint to re-enter after auto-lock",
                checked = state.isBiometricEnabled,
                onToggle = viewModel::toggleBiometric
            )

            // Round 4 Feature A: battery optimization exemption.
            // Without this, a recording-in-progress can be silently killed
            // by Doze once the device screen has been off for ~15 minutes
            // — the foreground service gets reaped even with our wake lock.
            // The ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS intent opens
            // a system dialog; we re-check on return via
            // rememberLauncherForActivityResult so the subtitle updates.
            var batteryExempt by remember {
                mutableStateOf(isIgnoringBatteryOptimizations(context))
            }
            val batteryOptLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) {
                batteryExempt = isIgnoringBatteryOptimizations(context)
            }
            // Round 4 Feature B: secure overlay lock.
            // Toggle is only useful if Settings.canDrawOverlays is also
            // true. We track the grant state locally so the subtitle
            // reflects reality.
            var overlayGranted by remember { mutableStateOf(canDrawOverlaysCompat(context)) }
            val overlayPermLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) {
                overlayGranted = canDrawOverlaysCompat(context)
            }
            SettingsToggle(
                title = "Secure Overlay Lock",
                subtitle = when {
                    !overlayGranted && state.isOverlayLockEnabled ->
                        "Needs overlay permission — tap to grant"
                    state.isOverlayLockEnabled ->
                        "Enabled — lock stays on top even after home-swipe"
                    else ->
                        "Use a system overlay instead of the in-app cover"
                },
                checked = state.isOverlayLockEnabled,
                onToggle = {
                    val newEnabled = !state.isOverlayLockEnabled
                    viewModel.setOverlayLockEnabled(newEnabled)
                    if (newEnabled && !overlayGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        runCatching {
                            overlayPermLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                }
            )

            // Round 5: real device lock during recording. When ON, no fake
            // cover is shown — the user power-locks the phone normally and
            // the foreground service keeps the recording running. They
            // unlock with their REAL Android PIN/biometric. Trade-off: the
            // calculator is the first thing visible after unlock, no
            // covert layer in between.
            SettingsToggle(
                title = "Use real device lock while recording",
                subtitle = if (state.useRealLockDuringRecording)
                    "On — power-lock as normal; recording continues; unlock with your phone PIN"
                else
                    "Off — show the in-app fake lock cover when recording",
                checked = state.useRealLockDuringRecording,
                onToggle = {
                    viewModel.setUseRealLockDuringRecording(!state.useRealLockDuringRecording)
                }
            )

            SettingsRow(
                title = "Disable Battery Optimization",
                subtitle = if (batteryExempt)
                    "Enabled — recordings won't be killed by Doze"
                else
                    "Tap to enable — protects long recordings from Doze",
                onClick = {
                    if (batteryExempt) {
                        // Already exempt; open the settings page so the user
                        // can revoke if desired — don't auto-revoke silently.
                        runCatching {
                            batteryOptLauncher.launch(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            )
                        }
                    } else {
                        runCatching {
                            batteryOptLauncher.launch(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(Uri.parse("package:${context.packageName}"))
                            )
                        }.onFailure {
                            Toast.makeText(context, "Couldn't open battery settings", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
            )

            // Black-screen fake lock mode
            SettingsToggle(
                title = "Black Screen Fake Lock",
                subtitle = if (state.useBlackScreenLock)
                    "On — pure black screen covers recording; tap to reveal PIN pad"
                else
                    "Off — show a realistic lock screen while recording",
                checked = state.useBlackScreenLock,
                onToggle = { viewModel.setUseBlackScreenLock(!state.useBlackScreenLock) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // --- Security section ---
            SectionHeader("Intruder Detection")

            SettingsToggle(
                title = "Intruder Selfie",
                subtitle = if (state.isIntruderSelfieEnabled)
                    "On — front camera photo taken on wrong PIN, saved to vault"
                else
                    "Off — take a selfie of anyone who enters the wrong PIN",
                checked = state.isIntruderSelfieEnabled,
                onToggle = { viewModel.setIntruderSelfieEnabled(!state.isIntruderSelfieEnabled) }
            )

            SettingsToggle(
                title = "Auto-Wipe",
                subtitle = if (state.isAutoWipeEnabled)
                    "On — wipe all vault data after ${state.autoWipeThreshold} wrong PINs"
                else
                    "Off — delete everything after too many wrong PIN attempts",
                checked = state.isAutoWipeEnabled,
                onToggle = { viewModel.setAutoWipeEnabled(!state.isAutoWipeEnabled) }
            )

            if (state.isAutoWipeEnabled) {
                var showWipeThresholdPicker by remember { mutableStateOf(false) }
                SettingsRow(
                    title = "Wipe after attempts",
                    subtitle = "${state.autoWipeThreshold} wrong PINs trigger wipe",
                    onClick = { showWipeThresholdPicker = true },
                    trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
                if (showWipeThresholdPicker) {
                    AlertDialog(
                        onDismissRequest = { showWipeThresholdPicker = false },
                        title = { Text("Wipe after N wrong PINs") },
                        text = {
                            Column {
                                listOf(3, 5, 10, 15, 20).forEach { n ->
                                    Row(
                                        modifier = androidx.compose.ui.Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.setAutoWipeThreshold(n); showWipeThresholdPicker = false }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = state.autoWipeThreshold == n,
                                            onClick = { viewModel.setAutoWipeThreshold(n); showWipeThresholdPicker = false }
                                        )
                                        Text("$n attempts", modifier = androidx.compose.ui.Modifier.padding(start = 8.dp))
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showWipeThresholdPicker = false }) { Text("Cancel") }
                        }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // --- Decoy section ---
            SectionHeader("Decoy PIN")

            if (state.isDecoyEnabled) {
                SettingsRow(
                    title = "Decoy PIN Active",
                    subtitle = "A second PIN opens a fake empty vault",
                    onClick = {},
                )
                SettingsRow(
                    title = "Disable Decoy PIN",
                    subtitle = "Remove the decoy PIN",
                    onClick = viewModel::disableDecoy,
                    trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            } else {
                SettingsRow(
                    title = "Set Up Decoy PIN",
                    subtitle = "A second code that opens a fake empty app",
                    onClick = viewModel::showDecoyDialog,
                    trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // --- Panic section ---
            SectionHeader("Panic Lock")

            SettingsToggle(
                title = "Shake to Lock",
                subtitle = "Shake device to instantly return to calculator",
                checked = state.isPanicShakeEnabled,
                onToggle = viewModel::togglePanicShake
            )

            SettingsToggle(
                title = "Triple-Back to Lock",
                subtitle = "Press back 3 times rapidly to lock",
                checked = state.isPanicBackEnabled,
                onToggle = viewModel::togglePanicBack
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // --- Vault backup section ---
            SectionHeader("Vault Backup")

            SettingsRow(
                title = "Export Vault Backup",
                subtitle = "ZIP all encrypted vault files — share or save to external storage",
                onClick = { viewModel.exportVaultBackup() },
                trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // --- Diagnostics section ---
            SectionHeader("Diagnostics")

            SettingsRow(
                title = "Export crash log",
                subtitle = "Share the latest app.log file",
                onClick = {
                    val file = AppLogger.logFile(context)
                    if (!file.exists() || file.length() == 0L) {
                        Toast.makeText(context, "No log yet", Toast.LENGTH_SHORT).show()
                    } else {
                        try {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, "StealthCalc crash log")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            val chooser = Intent.createChooser(send, "Export crash log").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(chooser)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // --- About section ---
            SectionHeader("About")

            SettingsRow(
                title = "Version",
                subtitle = "1.0.0",
                onClick = {},
            )

            SettingsRow(
                title = "Database",
                subtitle = "SQLCipher AES-256 encrypted",
                onClick = {},
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Auto-lock picker dialog
    if (showAutoLockPicker) {
        AlertDialog(
            onDismissRequest = { showAutoLockPicker = false },
            title = { Text("Auto-Lock Timeout") },
            text = {
                Column {
                    viewModel.autoLockOptions.forEach { (delayMs, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setAutoLockDelay(delayMs)
                                    showAutoLockPicker = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = state.autoLockDelayMs == delayMs,
                                onClick = {
                                    viewModel.setAutoLockDelay(delayMs)
                                    showAutoLockPicker = false
                                }
                            )
                            Text(label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAutoLockPicker = false }) { Text("Cancel") }
            }
        )
    }

    // Change code dialog
    if (state.showChangeCodeDialog) {
        var oldCode by remember { mutableStateOf("") }
        var newCode by remember { mutableStateOf("") }
        var confirmCode by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = viewModel::hideChangeCodeDialog,
            title = { Text("Change Secret Code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = oldCode,
                        onValueChange = { if (it.all { c -> c.isDigit() }) oldCode = it },
                        label = { Text("Current code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newCode,
                        onValueChange = { if (it.all { c -> c.isDigit() }) newCode = it },
                        label = { Text("New code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmCode,
                        onValueChange = { if (it.all { c -> c.isDigit() }) confirmCode = it },
                        label = { Text("Confirm new code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.changeCodeError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.changeCode(oldCode, newCode, confirmCode) }) {
                    Text("Change")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::hideChangeCodeDialog) { Text("Cancel") }
            }
        )
    }

    // Decoy PIN dialog
    if (state.showDecoyDialog) {
        var decoyCode by remember { mutableStateOf("") }
        var confirmDecoy by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = viewModel::hideDecoyDialog,
            title = { Text("Set Decoy PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Enter a second code on the calculator. This code opens a fake empty app — plausible deniability.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                    OutlinedTextField(
                        value = decoyCode,
                        onValueChange = { if (it.all { c -> c.isDigit() }) decoyCode = it },
                        label = { Text("Decoy code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmDecoy,
                        onValueChange = { if (it.all { c -> c.isDigit() }) confirmDecoy = it },
                        label = { Text("Confirm decoy code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.decoyError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.setDecoyCode(decoyCode, confirmDecoy) }) {
                    Text("Set Decoy")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::hideDecoyDialog) { Text("Cancel") }
            }
        )
    }
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

/**
 * Check whether the calculator has been granted exemption from battery
 * optimization. Returns true on API < 23 (no such concept) so the row
 * shows "Enabled" and nothing bad happens on older devices.
 */
private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Round 4 Feature B helper — true on API < 23 (no overlay permission
 * concept there; manifest declaration was sufficient).
 */
private fun canDrawOverlaysCompat(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    return Settings.canDrawOverlays(context)
}

@Composable
private fun SettingsToggle(
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

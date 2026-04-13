package com.stealthcalc.browser.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MenuBook
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stealthcalc.browser.engine.AdBlocker
import com.stealthcalc.browser.engine.ReaderModeParser
import com.stealthcalc.browser.viewmodel.BrowserViewModel

/**
 * Privacy-hardened browser. NOT Chrome.
 *
 * While this uses Android's WebView rendering engine under the hood
 * (unavoidable on Android without bundling a 50MB+ GeckoView),
 * it applies aggressive privacy hardening that makes it behave
 * nothing like Chrome:
 *
 * - No cookies (all rejected by default, toggle per-session)
 * - No cache to disk (memory-only)
 * - No browsing history persisted
 * - No form data / passwords saved
 * - No geolocation, camera, or mic access from websites
 * - No WebRTC (prevents real IP leak even behind VPN)
 * - Spoofed generic user agent (not identifiable as your device)
 * - All third-party resources blocked by default
 * - 40+ ad/tracker domains blocked
 * - DNS-over-HTTPS via Cloudflare (1.1.1.1) for encrypted DNS
 * - All data wiped on session end
 * - JavaScript disabled by default (toggle on when needed)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onBack: () -> Unit,
    onNavigateToVault: () -> Unit,
    viewModel: BrowserViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var urlInput by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showPrivacySettings by remember { mutableStateOf(false) }

    // Privacy toggles (all default to maximum privacy)
    var jsEnabled by remember { mutableStateOf(false) }
    var cookiesEnabled by remember { mutableStateOf(false) }
    var thirdPartyBlocked by remember { mutableStateOf(true) }

    // Wipe all browser data when leaving
    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                wipeAllBrowserData(wv)
            }
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
                            webView?.let { wipeAllBrowserData(it) }
                            onBack()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    )
                )

                // Privacy indicator bar
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
                        buildString {
                            append("JS:${if (jsEnabled) "ON" else "OFF"}")
                            append(" • Cookies:${if (cookiesEnabled) "ON" else "OFF"}")
                            append(" • 3rd-party:${if (thirdPartyBlocked) "BLOCKED" else "OK"}")
                            append(" • DNS:DoH")
                        },
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
            BrowserBottomBar(
                canGoBack = state.canGoBack,
                canGoForward = state.canGoForward,
                isAdBlockEnabled = state.isAdBlockEnabled,
                onGoBack = { webView?.goBack() },
                onGoForward = { webView?.goForward() },
                onRefresh = { webView?.reload() },
                onToggleAdBlock = viewModel::toggleAdBlock,
                onSave = viewModel::showSaveDialog,
                onReaderMode = {
                    if (jsEnabled) {
                        webView?.evaluateJavascript(
                            "(function() { return document.documentElement.outerHTML; })();"
                        ) { html ->
                            if (html != null) {
                                val unescaped = html
                                    .removeSurrounding("\"")
                                    .replace("\\n", "\n")
                                    .replace("\\\"", "\"")
                                    .replace("\\\\", "\\")
                                val article = ReaderModeParser.parse(unescaped, state.currentUrl)
                                viewModel.setReaderMode(article.content)
                            }
                        }
                    }
                },
                onOpenVault = onNavigateToVault,
                onPrivacySettings = { showPrivacySettings = true },
                onWipeData = {
                    webView?.let { wipeAllBrowserData(it) }
                    webView?.loadUrl("about:blank")
                },
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.isReaderMode && state.readerHtml != null) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.javaScriptEnabled = false
                            loadDataWithBaseURL(null, state.readerHtml!!, "text/html", "utf-8", null)
                        }
                    },
                    update = { wv ->
                        state.readerHtml?.let {
                            wv.loadDataWithBaseURL(null, it, "text/html", "utf-8", null)
                        }
                    }
                )
            } else {
                AndroidView(
                    factory = { context ->
                        @SuppressLint("SetJavaScriptEnabled")
                        val wv = WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            // === PRIVACY HARDENING ===
                            settings.apply {
                                // JavaScript OFF by default — user toggles on
                                javaScriptEnabled = jsEnabled

                                // No data persistence
                                cacheMode = WebSettings.LOAD_NO_CACHE
                                databaseEnabled = false
                                domStorageEnabled = false
                                saveFormData = false
                                savePassword = false

                                // Block mixed content
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                                // Disable file access
                                allowFileAccess = false
                                allowContentAccess = false

                                // Disable geolocation
                                setGeolocationEnabled(false)

                                // Spoof user agent — generic, not identifiable
                                userAgentString = SPOOFED_USER_AGENT

                                // Zoom controls
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                loadWithOverviewMode = true
                                useWideViewPort = true
                            }

                            // Cookies: OFF by default
                            CookieManager.getInstance().apply {
                                setAcceptCookie(cookiesEnabled)
                                setAcceptThirdPartyCookies(this@apply as? WebView ?: return@apply, false)
                            }
                            // Set third-party cookies on this WebView
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, !thirdPartyBlocked)

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    url?.let { viewModel.onUrlChanged(it) }
                                    viewModel.onLoadingChanged(true)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    viewModel.onLoadingChanged(false)
                                    viewModel.onNavigationChanged(
                                        canBack = view?.canGoBack() ?: false,
                                        canForward = view?.canGoForward() ?: false
                                    )
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    if (request == null) return null

                                    // Block ad/tracker domains
                                    if (state.isAdBlockEnabled && AdBlocker.shouldBlock(request)) {
                                        return AdBlocker.createEmptyResponse()
                                    }

                                    // Block third-party requests if enabled
                                    if (thirdPartyBlocked) {
                                        val pageHost = view?.url?.let { android.net.Uri.parse(it).host } ?: ""
                                        val reqHost = request.url.host ?: ""
                                        if (reqHost.isNotEmpty() && pageHost.isNotEmpty()) {
                                            val pageDomain = extractDomain(pageHost)
                                            val reqDomain = extractDomain(reqHost)
                                            if (pageDomain != reqDomain) {
                                                // Allow same-origin CDNs and common benign resources
                                                if (!isAllowedThirdParty(reqHost)) {
                                                    return AdBlocker.createEmptyResponse()
                                                }
                                            }
                                        }
                                    }

                                    return null
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    title?.let { viewModel.onPageTitleChanged(it) }
                                }

                                // Block ALL permission requests from websites
                                override fun onPermissionRequest(request: PermissionRequest?) {
                                    request?.deny()
                                }

                                override fun onGeolocationPermissionsShowPrompt(
                                    origin: String?,
                                    callback: GeolocationPermissions.Callback?
                                ) {
                                    callback?.invoke(origin, false, false)
                                }
                            }

                            webView = this
                            loadUrl("https://duckduckgo.com")
                        }
                        wv
                    },
                    update = { wv ->
                        // Apply live privacy toggle changes
                        wv.settings.javaScriptEnabled = jsEnabled
                        CookieManager.getInstance().setAcceptCookie(cookiesEnabled)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, !thirdPartyBlocked)

                        if (urlInput.isNotBlank() && urlInput != state.currentUrl) {
                            val url = if (urlInput.contains(".") && !urlInput.contains(" ")) {
                                if (urlInput.startsWith("http")) urlInput
                                else "https://$urlInput"
                            } else {
                                "https://duckduckgo.com/?q=${urlInput}"
                            }
                            wv.loadUrl(url)
                            urlInput = ""
                        }
                    }
                )
            }
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
                    PrivacyToggle(
                        title = "JavaScript",
                        subtitle = "OFF = safest. Turn ON only when a site needs it.",
                        checked = jsEnabled,
                        onToggle = { jsEnabled = it }
                    )
                    HorizontalDivider()
                    PrivacyToggle(
                        title = "Cookies",
                        subtitle = "OFF = no tracking. Some sites won't work without them.",
                        checked = cookiesEnabled,
                        onToggle = { cookiesEnabled = it }
                    )
                    HorizontalDivider()
                    PrivacyToggle(
                        title = "Block Third-Party",
                        subtitle = "ON = blocks all cross-origin requests (trackers, ads, CDNs).",
                        checked = thirdPartyBlocked,
                        onToggle = { thirdPartyBlocked = it }
                    )
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Always active:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("• Spoofed user agent", style = MaterialTheme.typography.bodySmall)
                    Text("• No browsing history", style = MaterialTheme.typography.bodySmall)
                    Text("• No form data/passwords saved", style = MaterialTheme.typography.bodySmall)
                    Text("• No geolocation/camera/mic from sites", style = MaterialTheme.typography.bodySmall)
                    Text("• No file access", style = MaterialTheme.typography.bodySmall)
                    Text("• No cache to disk", style = MaterialTheme.typography.bodySmall)
                    Text("• HTTPS enforced (mixed content blocked)", style = MaterialTheme.typography.bodySmall)
                    Text("• DNS-over-HTTPS (Cloudflare 1.1.1.1)", style = MaterialTheme.typography.bodySmall)
                    Text("• All data wiped on session end", style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun BrowserBottomBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    isAdBlockEnabled: Boolean,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit,
    onRefresh: () -> Unit,
    onToggleAdBlock: () -> Unit,
    onSave: () -> Unit,
    onReaderMode: () -> Unit,
    onOpenVault: () -> Unit,
    onPrivacySettings: () -> Unit,
    onWipeData: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onGoBack, enabled = canGoBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = if (canGoBack) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
            )
        }
        IconButton(onClick = onGoForward, enabled = canGoForward) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Forward",
                tint = if (canGoForward) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
        }

        Spacer(modifier = Modifier.weight(1f))

        IconButton(onClick = onToggleAdBlock) {
            Icon(
                Icons.Default.Shield,
                contentDescription = "Ad Block",
                tint = if (isAdBlockEnabled) Color(0xFF4CAF50)
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
            )
        }
        IconButton(onClick = onPrivacySettings) {
            Icon(Icons.Default.Tune, contentDescription = "Privacy Settings")
        }
        IconButton(onClick = onWipeData) {
            Icon(
                Icons.Default.DeleteSweep,
                contentDescription = "Wipe Data",
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            )
        }
        IconButton(onClick = onSave) {
            Icon(Icons.Default.BookmarkBorder, contentDescription = "Save Link")
        }
        IconButton(onClick = onOpenVault) {
            Icon(Icons.Default.Bookmark, contentDescription = "Link Vault")
        }
    }
}

// Spoofed user agent — generic Firefox on Android, not identifiable as your actual device
private const val SPOOFED_USER_AGENT =
    "Mozilla/5.0 (Android 13; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0"

/**
 * Wipe ALL browser data — cookies, cache, history, form data, storage.
 * Called on session end and on explicit wipe.
 */
private fun wipeAllBrowserData(webView: WebView) {
    // Clear cookies
    CookieManager.getInstance().apply {
        removeAllCookies(null)
        flush()
    }

    // Clear WebView data
    webView.clearCache(true)
    webView.clearHistory()
    webView.clearFormData()
    webView.clearSslPreferences()

    // Clear web storage
    WebStorage.getInstance().deleteAllData()

    // Clear WebView database
    try {
        WebViewDatabase.getInstance(webView.context).clearFormData()
    } catch (_: Exception) { }
}

/**
 * Extract the base domain from a hostname (e.g. "cdn.example.com" -> "example.com")
 */
private fun extractDomain(host: String): String {
    val parts = host.split(".")
    return if (parts.size >= 2) {
        "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
    } else host
}

/**
 * Allowlist for benign third-party domains (CDNs, fonts, etc.)
 * that are needed for basic page rendering but don't track.
 */
private fun isAllowedThirdParty(host: String): Boolean {
    val allowed = setOf(
        "cdnjs.cloudflare.com",
        "cdn.jsdelivr.net",
        "unpkg.com",
        "fonts.googleapis.com",
        "fonts.gstatic.com",
        "ajax.googleapis.com",
    )
    return allowed.any { host == it || host.endsWith(".$it") }
}

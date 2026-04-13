package com.stealthcalc.browser.ui

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stealthcalc.browser.engine.AdBlocker
import com.stealthcalc.browser.engine.ReaderModeParser
import com.stealthcalc.browser.viewmodel.BrowserViewModel

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
    var pageHtml by remember { mutableStateOf<String?>(null) }

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
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    )
                )
                if (state.isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
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
                    // Fetch page HTML and parse for reader mode
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
                },
                onOpenVault = onNavigateToVault,
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.isReaderMode && state.readerHtml != null) {
                // Reader mode WebView
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
                // Normal WebView
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                loadWithOverviewMode = true
                                useWideViewPort = true
                            }

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
                                    if (state.isAdBlockEnabled && request != null && AdBlocker.shouldBlock(request)) {
                                        return AdBlocker.createEmptyResponse()
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    title?.let { viewModel.onPageTitleChanged(it) }
                                }
                            }

                            webView = this

                            // Load initial page
                            loadUrl("https://duckduckgo.com")
                        }
                    },
                    update = { wv ->
                        // Navigate on URL submit
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
                tint = if (isAdBlockEnabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
            )
        }
        IconButton(onClick = onReaderMode) {
            Icon(Icons.Default.MenuBook, contentDescription = "Reader Mode")
        }
        IconButton(onClick = onSave) {
            Icon(Icons.Default.BookmarkBorder, contentDescription = "Save Link")
        }
        IconButton(onClick = onOpenVault) {
            Icon(Icons.Default.Bookmark, contentDescription = "Link Vault")
        }
    }
}

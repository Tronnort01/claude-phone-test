package com.stealthcalc.browser.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.browser.data.BrowserRepository
import com.stealthcalc.vault.data.VaultRepository
import com.stealthcalc.vault.service.FileEncryptionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BrowserScreenState(
    val currentUrl: String = "",
    val pageTitle: String = "",
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isReaderMode: Boolean = false,
    val readerHtml: String? = null,
    val isAdBlockEnabled: Boolean = true,
    val showSaveDialog: Boolean = false,
)

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val repository: BrowserRepository,
    private val vaultRepository: VaultRepository,
    private val encryptionService: FileEncryptionService,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowserScreenState())
    val state: StateFlow<BrowserScreenState> = _state.asStateFlow()

    fun onUrlChanged(url: String) {
        _state.update { it.copy(currentUrl = url) }
    }

    fun onPageTitleChanged(title: String) {
        _state.update { it.copy(pageTitle = title) }
    }

    fun onLoadingChanged(loading: Boolean) {
        _state.update { it.copy(isLoading = loading) }
    }

    fun onNavigationChanged(canBack: Boolean, canForward: Boolean) {
        _state.update { it.copy(canGoBack = canBack, canGoForward = canForward) }
    }

    fun toggleAdBlock() {
        _state.update { it.copy(isAdBlockEnabled = !it.isAdBlockEnabled) }
    }

    fun setReaderMode(html: String?) {
        _state.update { it.copy(isReaderMode = html != null, readerHtml = html) }
    }

    fun exitReaderMode() {
        _state.update { it.copy(isReaderMode = false, readerHtml = null) }
    }

    fun showSaveDialog() {
        _state.update { it.copy(showSaveDialog = true) }
    }

    fun hideSaveDialog() {
        _state.update { it.copy(showSaveDialog = false) }
    }

    fun savePageToVault() {
        val s = _state.value
        if (s.currentUrl.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val title = s.pageTitle.ifBlank { s.currentUrl }
                val safeName = title.take(40).replace("[^a-zA-Z0-9 ]".toRegex(), "_")
                val content = "Title: $title\nURL: ${s.currentUrl}\nSaved: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n"
                val tmpFile = java.io.File(System.getProperty("java.io.tmpdir"), "$safeName.txt")
                tmpFile.writeText(content)
                val uri = android.net.Uri.fromFile(tmpFile)
                val vaultFile = encryptionService.importFile(uri, "$safeName.txt", "text/plain")
                vaultRepository.saveFile(vaultFile)
                tmpFile.delete()
            }
        }
    }

    fun saveCurrentPage(collectionId: String? = null) {
        val s = _state.value
        if (s.currentUrl.isBlank()) return
        viewModelScope.launch {
            repository.saveLinkFromBrowser(
                url = s.currentUrl,
                title = s.pageTitle.ifBlank { s.currentUrl },
                collectionId = collectionId
            )
            _state.update { it.copy(showSaveDialog = false) }
        }
    }
}

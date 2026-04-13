package com.stealthcalc.browser.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.browser.data.BrowserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val repository: BrowserRepository
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

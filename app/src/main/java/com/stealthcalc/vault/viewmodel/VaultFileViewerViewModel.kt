package com.stealthcalc.vault.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.vault.data.VaultRepository
import com.stealthcalc.vault.model.VaultFile
import com.stealthcalc.vault.service.FileEncryptionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Holds the decrypted temp file for a single VaultFile while the viewer
 * is visible. Deletes the temp on clear so the plaintext copy never
 * lingers on disk after the user leaves the screen.
 */
@HiltViewModel
class VaultFileViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: VaultRepository,
    private val encryptionService: FileEncryptionService,
) : ViewModel() {

    private val fileId: String = checkNotNull(savedStateHandle["fileId"]) {
        "VaultFileViewer requires a fileId nav argument"
    }

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Loading)
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private var tempFile: File? = null

    init {
        viewModelScope.launch {
            try {
                val vaultFile = repository.getFileById(fileId)
                if (vaultFile == null) {
                    _state.value = ViewerState.Error("File not found")
                    return@launch
                }
                val temp = withContext(Dispatchers.IO) {
                    encryptionService.decryptToTempFile(vaultFile)
                }
                tempFile = temp
                _state.value = ViewerState.Loaded(vaultFile, temp)
            } catch (e: Exception) {
                _state.value = ViewerState.Error(e.message ?: "Failed to open file")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { tempFile?.delete() }
    }
}

sealed class ViewerState {
    data object Loading : ViewerState()
    data class Loaded(val file: VaultFile, val tempFile: File) : ViewerState()
    data class Error(val message: String) : ViewerState()
}

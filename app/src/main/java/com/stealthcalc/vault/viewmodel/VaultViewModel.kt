package com.stealthcalc.vault.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.vault.data.VaultRepository
import com.stealthcalc.vault.model.VaultFile
import com.stealthcalc.vault.model.VaultFileType
import com.stealthcalc.vault.model.VaultFolder
import com.stealthcalc.vault.service.FileEncryptionService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class VaultFilter { ALL, PHOTOS, VIDEOS, DOCUMENTS, FAVORITES }

data class VaultScreenState(
    val files: List<VaultFile> = emptyList(),
    val folders: List<VaultFolder> = emptyList(),
    val currentFolderId: String? = null,
    val filter: VaultFilter = VaultFilter.ALL,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val isImporting: Boolean = false,
    val importProgress: String = "",
    val totalSize: Long = 0,
    val fileCount: Int = 0,
    val isGridView: Boolean = true,
)

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val encryptionService: FileEncryptionService,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _currentFolderId = MutableStateFlow<String?>(null)
    private val _filter = MutableStateFlow(VaultFilter.ALL)
    private val _searchQuery = MutableStateFlow("")
    private val _isSearchActive = MutableStateFlow(false)
    private val _isImporting = MutableStateFlow(false)
    private val _importProgress = MutableStateFlow("")
    private val _isGridView = MutableStateFlow(true)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val files = combine(_currentFolderId, _filter, _searchQuery) { folderId, filter, query ->
        Triple(folderId, filter, query)
    }.flatMapLatest { (folderId, filter, query) ->
        when {
            query.isNotBlank() -> repository.searchFiles(query)
            filter == VaultFilter.PHOTOS -> repository.getFilesByType(VaultFileType.PHOTO)
            filter == VaultFilter.VIDEOS -> repository.getFilesByType(VaultFileType.VIDEO)
            filter == VaultFilter.DOCUMENTS -> repository.getFilesByType(VaultFileType.DOCUMENT)
            filter == VaultFilter.FAVORITES -> repository.getFavoriteFiles()
            folderId != null -> repository.getFilesByFolder(folderId)
            else -> repository.getRootFiles()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val folders = _currentFolderId.flatMapLatest { folderId ->
        if (folderId != null) repository.getSubFolders(folderId)
        else repository.getRootFolders()
    }

    val state: StateFlow<VaultScreenState> = combine(
        files,
        folders,
        _currentFolderId,
        _filter,
        _searchQuery,
        _isSearchActive,
        _isImporting,
        _importProgress,
        repository.getTotalSize(),
        repository.getFileCount(),
    ) { values ->
        VaultScreenState(
            files = values[0] as List<VaultFile>,
            folders = values[1] as List<VaultFolder>,
            currentFolderId = values[2] as String?,
            filter = values[3] as VaultFilter,
            searchQuery = values[4] as String,
            isSearchActive = values[5] as Boolean,
            isImporting = values[6] as Boolean,
            importProgress = values[7] as String,
            totalSize = (values[8] as Long?) ?: 0L,
            fileCount = values[9] as Int,
            isGridView = _isGridView.value,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VaultScreenState()
    )

    fun setFilter(filter: VaultFilter) {
        _filter.value = filter
        _currentFolderId.value = null
    }

    fun openFolder(folderId: String) {
        _currentFolderId.value = folderId
        _filter.value = VaultFilter.ALL
    }

    fun navigateUp() {
        _currentFolderId.value = null
    }

    fun toggleSearch() {
        _isSearchActive.value = !_isSearchActive.value
        if (!_isSearchActive.value) _searchQuery.value = ""
    }

    fun onSearchQueryChanged(query: String) { _searchQuery.value = query }
    fun toggleGridView() { _isGridView.value = !_isGridView.value }

    /**
     * Import files from content URIs. Encrypts each file, saves metadata to DB,
     * and optionally deletes the originals.
     */
    fun importFiles(uris: List<Uri>, deleteOriginals: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            val total = uris.size

            uris.forEachIndexed { index, uri ->
                try {
                    _importProgress.value = "Encrypting ${index + 1} of $total..."

                    val (name, mimeType) = getFileInfo(uri)
                    val vaultFile = encryptionService.importFile(uri, name, mimeType)
                        .copy(folderId = _currentFolderId.value)
                    repository.saveFile(vaultFile)

                    // Delete original if requested
                    if (deleteOriginals) {
                        try {
                            appContext.contentResolver.delete(uri, null, null)
                        } catch (_: Exception) {
                            // Some URIs can't be deleted (read-only), that's ok
                        }
                    }
                } catch (e: Exception) {
                    _importProgress.value = "Failed: ${e.message}"
                }
            }

            _isImporting.value = false
            _importProgress.value = ""
        }
    }

    fun deleteFile(file: VaultFile) {
        viewModelScope.launch { repository.deleteFile(file) }
    }

    fun toggleFavorite(fileId: String) {
        viewModelScope.launch { repository.toggleFavorite(fileId) }
    }

    fun renameFile(id: String, name: String) {
        viewModelScope.launch { repository.renameFile(id, name) }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            repository.createFolder(name, parentId = _currentFolderId.value)
        }
    }

    fun moveToFolder(fileId: String, folderId: String?) {
        viewModelScope.launch { repository.moveToFolder(fileId, folderId) }
    }

    /**
     * Get a decrypted temp file for viewing/playing.
     */
    fun getDecryptedFile(vaultFile: VaultFile): java.io.File {
        return encryptionService.decryptToTempFile(vaultFile)
    }

    private fun getFileInfo(uri: Uri): Pair<String, String> {
        var name = "file"
        var mimeType = appContext.contentResolver.getType(uri) ?: "application/octet-stream"

        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex) ?: "file"
            }
        }

        return Pair(name, mimeType)
    }
}

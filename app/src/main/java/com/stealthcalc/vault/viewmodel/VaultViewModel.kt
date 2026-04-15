package com.stealthcalc.vault.viewmodel

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.vault.data.VaultRepository
import com.stealthcalc.vault.model.VaultFile
import com.stealthcalc.vault.model.VaultFileType
import com.stealthcalc.vault.model.VaultFolder
import com.stealthcalc.vault.model.VaultSortOrder
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
    val sortOrder: VaultSortOrder = VaultSortOrder.DATE_NEWEST,
    val totalSize: Long = 0,
    val fileCount: Int = 0,
    val isGridView: Boolean = true,
)

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repository: VaultRepository,
    val encryptionService: FileEncryptionService,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _currentFolderId = MutableStateFlow<String?>(null)
    private val _filter = MutableStateFlow(VaultFilter.ALL)
    private val _searchQuery = MutableStateFlow("")
    private val _isSearchActive = MutableStateFlow(false)
    private val _isImporting = MutableStateFlow(false)
    private val _importProgress = MutableStateFlow("")
    private val _isGridView = MutableStateFlow(true)
    private val _sortOrder = MutableStateFlow(VaultSortOrder.DATE_NEWEST)

    // Holds an IntentSender for MediaStore delete requests that need explicit
    // user approval (API 29 RecoverableSecurityException, API 30+
    // createDeleteRequest). The UI collects this and launches the IntentSender
    // via rememberLauncherForActivityResult; the system shows the system
    // confirmation dialog and actually deletes the originals on OK.
    private val _pendingDeleteRequest = MutableStateFlow<IntentSender?>(null)
    val pendingDeleteRequest: StateFlow<IntentSender?> = _pendingDeleteRequest.asStateFlow()

    // Round 5: multi-select for the bulk-export and bulk-delete actions.
    // VaultScreen enters selection mode on long-press of any card; subsequent
    // taps toggle membership. Top app bar swaps to a contextual toolbar
    // showing count + Export + Delete + Cancel while this set is non-empty.
    private val _selectedFileIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedFileIds: StateFlow<Set<String>> = _selectedFileIds.asStateFlow()

    // One-shot event consumed by the UI to show a snackbar after an export
    // finishes. Null means no pending event (UI clears after showing).
    private val _exportEvent = MutableStateFlow<ExportEvent?>(null)
    val exportEvent: StateFlow<ExportEvent?> = _exportEvent.asStateFlow()

    data class ExportEvent(val success: Int, val total: Int)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val files = combine(_currentFolderId, _filter, _searchQuery, _sortOrder) { values ->
        @Suppress("UNCHECKED_CAST")
        val folderId = values[0] as String?
        val filter = values[1] as VaultFilter
        val query = values[2] as String
        val sort = values[3] as VaultSortOrder
        FileQuery(folderId, filter, query, sort)
    }.flatMapLatest { q ->
        when {
            q.query.isNotBlank() -> repository.searchFiles(q.query)
            q.filter == VaultFilter.PHOTOS -> repository.getFiles(type = VaultFileType.PHOTO, sort = q.sort)
            q.filter == VaultFilter.VIDEOS -> repository.getFiles(type = VaultFileType.VIDEO, sort = q.sort)
            q.filter == VaultFilter.DOCUMENTS -> repository.getFiles(type = VaultFileType.DOCUMENT, sort = q.sort)
            q.filter == VaultFilter.FAVORITES -> repository.getFiles(favoritesOnly = true, sort = q.sort)
            q.folderId != null -> repository.getFiles(folderId = q.folderId, sort = q.sort)
            else -> repository.getFiles(rootOnly = true, sort = q.sort)
        }
    }

    private data class FileQuery(val folderId: String?, val filter: VaultFilter, val query: String, val sort: VaultSortOrder)

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
            sortOrder = _sortOrder.value,
            isGridView = _isGridView.value,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VaultScreenState()
    )

    fun setSortOrder(sort: VaultSortOrder) {
        _sortOrder.value = sort
    }

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
            val successfullyImported = mutableListOf<Uri>()

            uris.forEachIndexed { index, uri ->
                try {
                    _importProgress.value = "Encrypting ${index + 1} of $total..."

                    val (name, mimeType) = getFileInfo(uri)
                    val vaultFile = encryptionService.importFile(uri, name, mimeType)
                        .copy(folderId = _currentFolderId.value)
                    repository.saveFile(vaultFile)
                    successfullyImported.add(uri)
                } catch (e: Exception) {
                    _importProgress.value = "Failed: ${e.message}"
                }
            }

            if (deleteOriginals && successfullyImported.isNotEmpty()) {
                requestOriginalsDeletion(successfullyImported)
            }

            _isImporting.value = false
            _importProgress.value = ""
        }
    }

    /**
     * Delete the original gallery entries for the given URIs, using the
     * per-API-level flow:
     *  - API 30+: bulk MediaStore.createDeleteRequest → one system dialog
     *    approves all deletes.
     *  - API 29: try direct delete per URI. If one throws
     *    RecoverableSecurityException, expose its IntentSender; the user
     *    approves, then any remaining URIs will be handled on a subsequent
     *    import (we don't re-drive the loop from here to keep this simple).
     *  - API 28 and below: direct delete works without any user prompt.
     *
     * On API 33+ the system photo picker returns read-only
     * content://media/picker URIs that are NOT MediaStore entries, so
     * createDeleteRequest on them is a silent no-op. To actually delete
     * the original gallery copy we first map each picker URI to its
     * MediaStore URI by matching (DISPLAY_NAME, SIZE). That lookup
     * requires READ_MEDIA_IMAGES / READ_MEDIA_VIDEO permission; if the
     * user hasn't granted those, the mapping returns nothing and the
     * failure is logged for the Export-crash-log flow.
     *
     * The originals are only removed on user confirmation — never silently.
     */
    private fun requestOriginalsDeletion(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val resolvedUris = uris.mapNotNull { resolveToMediaStoreUri(it) ?: it.takeIf { isMediaStoreUri(it) } }
        if (resolvedUris.isEmpty()) {
            AppLogger.log(
                appContext,
                "vault",
                "Import delete skipped: ${uris.size} picker URIs did not resolve to MediaStore entries. " +
                    "Grant Photos/Videos permission so originals can be removed after import."
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            publishBulkDeleteRequest(resolvedUris)
            return
        }
        for (uri in resolvedUris) {
            try {
                appContext.contentResolver.delete(uri, null, null)
            } catch (rse: RecoverableSecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    _pendingDeleteRequest.value = rse.userAction.actionIntent.intentSender
                    return
                }
            } catch (_: Exception) {
                // Some URIs can't be deleted (read-only); skip and move on.
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun publishBulkDeleteRequest(uris: List<Uri>) {
        val pendingIntent = MediaStore.createDeleteRequest(appContext.contentResolver, uris)
        _pendingDeleteRequest.value = pendingIntent.intentSender
    }

    /**
     * Look up the MediaStore URI that corresponds to a picker-returned
     * content URI. The photo picker (`content://media/picker/...`) returns
     * ephemeral read-only grants that can't be deleted; MediaStore
     * (`content://media/external/images/...`) URIs can.
     *
     * We match by (DISPLAY_NAME, SIZE) against the Images / Video / Audio
     * collection. Requires READ_MEDIA_IMAGES/VIDEO (API 33+) or
     * READ_EXTERNAL_STORAGE (<33) — without it the query returns no rows
     * and this method returns null.
     */
    private fun resolveToMediaStoreUri(pickerUri: Uri): Uri? {
        if (isMediaStoreUri(pickerUri)) return pickerUri
        return try {
            val resolver = appContext.contentResolver
            val mimeType = resolver.getType(pickerUri) ?: ""
            val collection = when {
                mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> return null
            }

            // Read display name + size from the picker URI (always permitted).
            var displayName: String? = null
            var size: Long = -1L
            resolver.query(
                pickerUri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx)
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
            if (displayName.isNullOrBlank() || size <= 0) return null

            // Find the MediaStore row with the same display name + size.
            // Needs READ_MEDIA_* / READ_EXTERNAL_STORAGE — if denied, the
            // query silently returns no rows and we fall through to null.
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.SIZE}=?"
            val args = arrayOf(displayName!!, size.toString())
            resolver.query(collection, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    return ContentUris.withAppendedId(collection, id)
                }
            }
            null
        } catch (e: Exception) {
            AppLogger.log(appContext, "vault", "resolveToMediaStoreUri failed: ${e.message}")
            null
        }
    }

    /** True for URIs like content://media/external/images/media/123. */
    private fun isMediaStoreUri(uri: Uri): Boolean {
        val path = uri.path ?: return false
        return uri.authority == MediaStore.AUTHORITY &&
            (path.startsWith("/external/") || path.startsWith("/internal/"))
    }

    /** Called by the UI after the delete-confirmation launcher returns. */
    fun onDeleteRequestHandled() {
        _pendingDeleteRequest.value = null
    }

    // --- Selection / bulk export --------------------------------------------

    fun toggleSelection(fileId: String) {
        _selectedFileIds.update { current ->
            if (fileId in current) current - fileId else current + fileId
        }
    }

    fun clearSelection() {
        _selectedFileIds.value = emptySet()
    }

    fun onExportEventHandled() {
        _exportEvent.value = null
    }

    /**
     * Decrypt every currently-selected vault file and write it to the public
     * media library via MediaStore. Photos land in Pictures/StealthCalc,
     * videos in Movies/StealthCalc, audio in Music/StealthCalc — they then
     * appear in Google Photos / Gallery / Files.
     *
     * The export streams plaintext directly into the MediaStore-owned
     * OutputStream, so no plaintext copy ever sits in cacheDir. Selection
     * is cleared after the run; the UI is notified via [exportEvent].
     */
    fun exportSelected() {
        val ids = _selectedFileIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val current = state.value.files.filter { it.id in ids }
            var success = 0
            for (file in current) {
                val uri = encryptionService.exportToMediaStore(file)
                if (uri != null) success++
            }
            _exportEvent.value = ExportEvent(success = success, total = current.size)
            _selectedFileIds.value = emptySet()
        }
    }

    /**
     * Bulk delete every currently-selected vault file. Mirrors the existing
     * single-file deleteFile() but loops over the selection. Vault payload +
     * thumbnail are secure-deleted by VaultRepository.deleteFile; cascade to
     * the linked Recording (if any) is handled there too.
     */
    fun deleteSelected() {
        val ids = _selectedFileIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val current = state.value.files.filter { it.id in ids }
            for (file in current) {
                repository.deleteFile(file)
            }
            _selectedFileIds.value = emptySet()
        }
    }

    fun saveImportedFile(file: VaultFile) {
        viewModelScope.launch { repository.saveFile(file) }
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

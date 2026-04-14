package com.stealthcalc.vault.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.vault.data.VaultRepository
import com.stealthcalc.vault.model.VaultFile
import com.stealthcalc.vault.model.VaultFileType
import com.stealthcalc.vault.model.VaultSortOrder
import com.stealthcalc.vault.service.FileEncryptionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Loads the list of vault files of the same type as the initially-tapped
 * one so the viewer can page between them horizontally. Decrypts each
 * file on demand (cached per fileId) and cleans up all temp files on
 * clear so plaintext copies never linger.
 */
@HiltViewModel
class VaultFileViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: VaultRepository,
    private val encryptionService: FileEncryptionService,
) : ViewModel() {

    private val initialFileId: String = checkNotNull(savedStateHandle["fileId"]) {
        "VaultFileViewer requires a fileId nav argument"
    }

    private val _files = MutableStateFlow<List<VaultFile>>(emptyList())
    val files: StateFlow<List<VaultFile>> = _files.asStateFlow()

    private val _initialIndex = MutableStateFlow(0)
    val initialIndex: StateFlow<Int> = _initialIndex.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    // Per-fileId cache of decrypted plaintext temp files. Keeps swiping
    // back and forth cheap; everything is deleted in onCleared().
    private val tempCache = mutableMapOf<String, File>()
    private val decryptMutex = Mutex()

    init {
        viewModelScope.launch {
            try {
                val initialFile = repository.getFileById(initialFileId)
                if (initialFile == null) {
                    _loadError.value = "File not found"
                    return@launch
                }
                // Paginate over files of the same type, newest first. So
                // opening a photo lets the user swipe through all photos,
                // opening a video swipes through videos, etc. Keeps the
                // viewer focused instead of jumping across mime types.
                val allSameType = repository
                    .getFiles(type = initialFile.fileType, sort = VaultSortOrder.DATE_NEWEST)
                    .first()
                val list = if (allSameType.any { it.id == initialFileId }) {
                    allSameType
                } else {
                    // File exists but falls outside the filtered query (e.g.
                    // OTHER). Fall back to just the single file.
                    listOf(initialFile)
                }
                _files.value = list
                _initialIndex.value = list
                    .indexOfFirst { it.id == initialFileId }
                    .coerceAtLeast(0)
            } catch (e: Exception) {
                _loadError.value = e.message ?: "Failed to load vault"
            }
        }
    }

    /**
     * Decrypt a vault file to a cached plaintext temp. Safe to call from
     * a LaunchedEffect — per-fileId Mutex prevents duplicate work, cache
     * keeps repeat calls O(1).
     */
    suspend fun decrypt(file: VaultFile): File? = withContext(Dispatchers.IO) {
        decryptMutex.withLock {
            tempCache[file.id]?.let { if (it.exists()) return@withContext it }
            runCatching { encryptionService.decryptToTempFile(file) }
                .getOrNull()
                ?.also { tempCache[file.id] = it }
        }
    }

    /**
     * Limit the temp cache so very long pager swipes don't blow up cacheDir.
     * Keeps [keepAround] decrypted files around [centerIndex] and deletes
     * the rest from disk + cache.
     */
    fun trimCache(centerIndex: Int, keepAround: Int = 2) {
        val list = _files.value
        if (list.isEmpty()) return
        val keep = buildSet {
            for (delta in -keepAround..keepAround) {
                val i = centerIndex + delta
                if (i in list.indices) add(list[i].id)
            }
        }
        val iter = tempCache.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key !in keep) {
                runCatching { entry.value.delete() }
                iter.remove()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tempCache.values.forEach { runCatching { it.delete() } }
        tempCache.clear()
    }
}

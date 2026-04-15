package com.stealthcalc.vault.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.vault.data.VaultRepository
import com.stealthcalc.vault.model.VaultFile
import com.stealthcalc.vault.model.VaultFileType
import com.stealthcalc.vault.service.FileEncryptionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs the "pick a second photo to merge with" screen. Just lists every
 * PHOTO in the vault, minus the base photo the user is editing. Thumbnails
 * are decrypted on-demand by the existing FileEncryptionService — no extra
 * caching layer here, the grid is small enough that produceState in the
 * card composable handles it.
 */
@HiltViewModel
class PhotoMergePickerViewModel @Inject constructor(
    private val repository: VaultRepository,
    val encryptionService: FileEncryptionService,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val baseId: String = savedStateHandle["baseId"] ?: ""

    val photos: StateFlow<List<VaultFile>> = repository
        .getFiles(type = VaultFileType.PHOTO)
        .map { list -> list.filter { it.id != baseId } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )
}

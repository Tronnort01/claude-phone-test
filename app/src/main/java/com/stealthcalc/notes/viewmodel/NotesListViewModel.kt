package com.stealthcalc.notes.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.notes.data.NoteRepository
import com.stealthcalc.notes.model.NoteFolder
import com.stealthcalc.notes.model.NoteWithTags
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotesListState(
    val notes: List<NoteWithTags> = emptyList(),
    val folders: List<NoteFolder> = emptyList(),
    val selectedFolderId: String? = null,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val showTrash: Boolean = false,
    val isGridView: Boolean = true,
)

@HiltViewModel
class NotesListViewModel @Inject constructor(
    private val repository: NoteRepository
) : ViewModel() {

    private val _selectedFolderId = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _showTrash = MutableStateFlow(false)
    private val _isSearchActive = MutableStateFlow(false)
    private val _isGridView = MutableStateFlow(true)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val notes = combine(_selectedFolderId, _searchQuery, _showTrash) { folderId, query, trash ->
        Triple(folderId, query, trash)
    }.flatMapLatest { (folderId, query, trash) ->
        when {
            trash -> repository.getTrashNotes()
            query.isNotBlank() -> repository.searchNotes(query)
            folderId != null -> repository.getNotesByFolder(folderId)
            else -> repository.getAllNotes()
        }
    }

    val state: StateFlow<NotesListState> = combine(
        notes,
        repository.getAllFolders(),
        _selectedFolderId,
        _searchQuery,
        _isSearchActive,
        _showTrash,
        _isGridView,
    ) { values ->
        NotesListState(
            notes = values[0] as List<NoteWithTags>,
            folders = values[1] as List<NoteFolder>,
            selectedFolderId = values[2] as String?,
            searchQuery = values[3] as String,
            isSearchActive = values[4] as Boolean,
            showTrash = values[5] as Boolean,
            isGridView = values[6] as Boolean,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NotesListState()
    )

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun toggleSearch() {
        _isSearchActive.value = !_isSearchActive.value
        if (!_isSearchActive.value) _searchQuery.value = ""
    }

    fun selectFolder(folderId: String?) {
        _selectedFolderId.value = folderId
        _showTrash.value = false
    }

    fun showTrash() {
        _showTrash.value = true
        _selectedFolderId.value = null
    }

    fun toggleViewMode() {
        _isGridView.value = !_isGridView.value
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch { repository.deleteNote(noteId) }
    }

    fun restoreNote(noteId: String) {
        viewModelScope.launch { repository.restoreNote(noteId) }
    }

    fun togglePin(noteId: String) {
        viewModelScope.launch { repository.togglePin(noteId) }
    }

    fun createFolder(name: String) {
        viewModelScope.launch { repository.createFolder(name) }
    }

    fun deleteFolder(folder: NoteFolder) {
        viewModelScope.launch { repository.deleteFolder(folder) }
    }
}

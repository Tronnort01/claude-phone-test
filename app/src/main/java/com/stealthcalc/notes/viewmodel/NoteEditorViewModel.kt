package com.stealthcalc.notes.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.notes.data.NoteRepository
import com.stealthcalc.notes.model.Note
import com.stealthcalc.notes.model.NoteTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NoteEditorState(
    val noteId: String = "",
    val title: String = "",
    val content: String = "",
    val folderId: String? = null,
    val color: Int? = null,
    val isPinned: Boolean = false,
    val tags: List<NoteTag> = emptyList(),
    val isNew: Boolean = true,
    val isSaving: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
)

@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    private val repository: NoteRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val noteId: String? = savedStateHandle["noteId"]

    private val _state = MutableStateFlow(NoteEditorState())
    val state: StateFlow<NoteEditorState> = _state.asStateFlow()

    val allTags: StateFlow<List<NoteTag>> = repository.getAllTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        if (noteId != null) {
            loadNote(noteId)
        } else {
            _state.value = NoteEditorState(
                noteId = java.util.UUID.randomUUID().toString(),
                isNew = true
            )
        }
    }

    private fun loadNote(id: String) {
        viewModelScope.launch {
            val note = repository.getNoteById(id)
            if (note != null) {
                _state.value = NoteEditorState(
                    noteId = note.id,
                    title = note.title,
                    content = note.content,
                    folderId = note.folderId,
                    color = note.color,
                    isPinned = note.isPinned,
                    isNew = false,
                )
            }
        }
    }

    fun onTitleChanged(title: String) {
        _state.update { it.copy(title = title, hasUnsavedChanges = true) }
    }

    fun onContentChanged(content: String) {
        _state.update { it.copy(content = content, hasUnsavedChanges = true) }
    }

    fun onColorChanged(color: Int?) {
        _state.update { it.copy(color = color, hasUnsavedChanges = true) }
    }

    fun togglePin() {
        _state.update { it.copy(isPinned = !it.isPinned, hasUnsavedChanges = true) }
    }

    fun save() {
        val current = _state.value
        if (current.title.isBlank() && current.content.isBlank()) return

        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }

            val note = Note(
                id = current.noteId,
                title = current.title,
                content = current.content,
                folderId = current.folderId,
                color = current.color,
                isPinned = current.isPinned,
            )
            repository.saveNote(note)

            _state.update { it.copy(isSaving = false, hasUnsavedChanges = false, isNew = false) }
        }
    }

    fun delete() {
        viewModelScope.launch {
            repository.deleteNote(_state.value.noteId)
        }
    }
}

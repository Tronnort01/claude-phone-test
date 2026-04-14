package com.stealthcalc.notes.data

import com.stealthcalc.notes.model.Note
import com.stealthcalc.notes.model.NoteFolder
import com.stealthcalc.notes.model.NoteTag
import com.stealthcalc.notes.model.NoteTagCrossRef
import com.stealthcalc.notes.model.NoteWithTags
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoteRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val folderDao: NoteFolderDao,
    private val tagDao: NoteTagDao
) {
    // --- Notes ---

    fun getAllNotes(): Flow<List<NoteWithTags>> = noteDao.getAllNotes()

    fun getNotesByFolder(folderId: String): Flow<List<NoteWithTags>> =
        noteDao.getNotesByFolder(folderId)

    fun searchNotes(query: String): Flow<List<NoteWithTags>> = noteDao.searchNotes(query)

    fun getTrashNotes(): Flow<List<NoteWithTags>> = noteDao.getTrashNotes()

    suspend fun getNoteById(noteId: String): Note? = noteDao.getNoteById(noteId)

    suspend fun saveNote(note: Note) {
        val existing = noteDao.getNoteById(note.id)
        if (existing != null) {
            noteDao.updateNote(note.copy(updatedAt = System.currentTimeMillis()))
        } else {
            noteDao.insertNote(note)
        }
    }

    suspend fun deleteNote(noteId: String) = noteDao.softDeleteNote(noteId)

    suspend fun restoreNote(noteId: String) = noteDao.restoreNote(noteId)

    suspend fun permanentlyDeleteNote(note: Note) = noteDao.permanentlyDeleteNote(note)

    suspend fun purgeTrash(daysOld: Int = 30) {
        val cutoff = System.currentTimeMillis() - (daysOld.toLong() * 24 * 60 * 60 * 1000)
        noteDao.purgeOldTrash(cutoff)
    }

    suspend fun togglePin(noteId: String) {
        val note = noteDao.getNoteById(noteId) ?: return
        noteDao.setPinned(noteId, !note.isPinned)
    }

    suspend fun setColor(noteId: String, color: Int?) = noteDao.setColor(noteId, color)

    suspend fun moveToFolder(noteId: String, folderId: String?) =
        noteDao.moveToFolder(noteId, folderId)

    // --- Folders ---

    fun getAllFolders(): Flow<List<NoteFolder>> = folderDao.getAllFolders()

    suspend fun createFolder(name: String): NoteFolder {
        val folder = NoteFolder(name = name)
        folderDao.insertFolder(folder)
        return folder
    }

    suspend fun updateFolder(folder: NoteFolder) = folderDao.updateFolder(folder)

    suspend fun deleteFolder(folder: NoteFolder) = folderDao.deleteFolder(folder)

    // --- Tags ---

    fun getAllTags(): Flow<List<NoteTag>> = tagDao.getAllTags()

    suspend fun createTag(name: String, color: Int? = null): NoteTag {
        val tag = NoteTag(name = name, color = color)
        tagDao.insertTag(tag)
        return tag
    }

    suspend fun deleteTag(tag: NoteTag) = tagDao.deleteTag(tag)

    suspend fun addTagToNote(noteId: String, tagId: String) =
        tagDao.addTagToNote(NoteTagCrossRef(noteId, tagId))

    suspend fun removeTagFromNote(noteId: String, tagId: String) =
        tagDao.removeTagFromNote(NoteTagCrossRef(noteId, tagId))

    suspend fun setNoteTags(noteId: String, tagIds: List<String>) {
        tagDao.removeAllTagsFromNote(noteId)
        tagIds.forEach { tagId ->
            tagDao.addTagToNote(NoteTagCrossRef(noteId, tagId))
        }
    }
}

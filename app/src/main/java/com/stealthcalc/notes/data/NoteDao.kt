package com.stealthcalc.notes.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.stealthcalc.notes.model.Note
import com.stealthcalc.notes.model.NoteWithTags
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Transaction
    @Query("""
        SELECT * FROM notes
        WHERE isDeleted = 0
        ORDER BY isPinned DESC, updatedAt DESC
    """)
    fun getAllNotes(): Flow<List<NoteWithTags>>

    @Transaction
    @Query("""
        SELECT * FROM notes
        WHERE isDeleted = 0 AND folderId = :folderId
        ORDER BY isPinned DESC, updatedAt DESC
    """)
    fun getNotesByFolder(folderId: String): Flow<List<NoteWithTags>>

    @Transaction
    @Query("""
        SELECT * FROM notes
        WHERE isDeleted = 0 AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%')
        ORDER BY isPinned DESC, updatedAt DESC
    """)
    fun searchNotes(query: String): Flow<List<NoteWithTags>>

    @Transaction
    @Query("SELECT * FROM notes WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun getTrashNotes(): Flow<List<NoteWithTags>>

    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNoteById(noteId: String): Note?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note)

    @Update
    suspend fun updateNote(note: Note)

    @Query("UPDATE notes SET isDeleted = 1, deletedAt = :timestamp WHERE id = :noteId")
    suspend fun softDeleteNote(noteId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET isDeleted = 0, deletedAt = NULL WHERE id = :noteId")
    suspend fun restoreNote(noteId: String)

    @Delete
    suspend fun permanentlyDeleteNote(note: Note)

    @Query("DELETE FROM notes WHERE isDeleted = 1 AND deletedAt < :cutoffTimestamp")
    suspend fun purgeOldTrash(cutoffTimestamp: Long)

    @Query("UPDATE notes SET isPinned = :isPinned WHERE id = :noteId")
    suspend fun setPinned(noteId: String, isPinned: Boolean)

    @Query("UPDATE notes SET color = :color WHERE id = :noteId")
    suspend fun setColor(noteId: String, color: Int?)

    @Query("UPDATE notes SET folderId = :folderId WHERE id = :noteId")
    suspend fun moveToFolder(noteId: String, folderId: String?)
}

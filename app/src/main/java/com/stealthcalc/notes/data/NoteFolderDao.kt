package com.stealthcalc.notes.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stealthcalc.notes.model.NoteFolder
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteFolderDao {

    @Query("SELECT * FROM note_folders ORDER BY sortOrder ASC, name ASC")
    fun getAllFolders(): Flow<List<NoteFolder>>

    @Query("SELECT * FROM note_folders WHERE id = :folderId")
    suspend fun getFolderById(folderId: String): NoteFolder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: NoteFolder)

    @Update
    suspend fun updateFolder(folder: NoteFolder)

    @Delete
    suspend fun deleteFolder(folder: NoteFolder)

    @Query("SELECT COUNT(*) FROM notes WHERE folderId = :folderId AND isDeleted = 0")
    fun getNoteCountInFolder(folderId: String): Flow<Int>
}

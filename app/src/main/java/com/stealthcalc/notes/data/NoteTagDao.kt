package com.stealthcalc.notes.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stealthcalc.notes.model.NoteTag
import com.stealthcalc.notes.model.NoteTagCrossRef
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteTagDao {

    @Query("SELECT * FROM note_tags ORDER BY name ASC")
    fun getAllTags(): Flow<List<NoteTag>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: NoteTag)

    @Delete
    suspend fun deleteTag(tag: NoteTag)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTagToNote(crossRef: NoteTagCrossRef)

    @Delete
    suspend fun removeTagFromNote(crossRef: NoteTagCrossRef)

    @Query("DELETE FROM note_tag_cross_ref WHERE noteId = :noteId")
    suspend fun removeAllTagsFromNote(noteId: String)

    @Query("""
        SELECT note_tags.* FROM note_tags
        INNER JOIN note_tag_cross_ref ON note_tags.id = note_tag_cross_ref.tagId
        WHERE note_tag_cross_ref.noteId = :noteId
    """)
    suspend fun getTagsForNote(noteId: String): List<NoteTag>
}

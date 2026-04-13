package com.stealthcalc.browser.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stealthcalc.browser.model.SavedLink
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedLinkDao {

    @Query("SELECT * FROM saved_links ORDER BY createdAt DESC")
    fun getAllLinks(): Flow<List<SavedLink>>

    @Query("SELECT * FROM saved_links WHERE collectionId = :collectionId ORDER BY createdAt DESC")
    fun getLinksByCollection(collectionId: String): Flow<List<SavedLink>>

    @Query("""
        SELECT * FROM saved_links
        WHERE title LIKE '%' || :query || '%'
           OR url LIKE '%' || :query || '%'
           OR notes LIKE '%' || :query || '%'
        ORDER BY createdAt DESC
    """)
    fun searchLinks(query: String): Flow<List<SavedLink>>

    @Query("SELECT * FROM saved_links WHERE id = :id")
    suspend fun getLinkById(id: String): SavedLink?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLink(link: SavedLink)

    @Update
    suspend fun updateLink(link: SavedLink)

    @Delete
    suspend fun deleteLink(link: SavedLink)
}

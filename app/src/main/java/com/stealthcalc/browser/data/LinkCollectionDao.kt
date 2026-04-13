package com.stealthcalc.browser.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stealthcalc.browser.model.LinkCollection
import kotlinx.coroutines.flow.Flow

@Dao
interface LinkCollectionDao {

    @Query("SELECT * FROM link_collections ORDER BY sortOrder ASC, name ASC")
    fun getAllCollections(): Flow<List<LinkCollection>>

    @Query("SELECT * FROM link_collections WHERE id = :id")
    suspend fun getCollectionById(id: String): LinkCollection?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: LinkCollection)

    @Update
    suspend fun updateCollection(collection: LinkCollection)

    @Delete
    suspend fun deleteCollection(collection: LinkCollection)

    @Query("SELECT COUNT(*) FROM saved_links WHERE collectionId = :collectionId")
    fun getLinkCountInCollection(collectionId: String): Flow<Int>
}

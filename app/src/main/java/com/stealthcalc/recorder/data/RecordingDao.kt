package com.stealthcalc.recorder.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stealthcalc.recorder.model.Recording
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun getAllRecordings(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE type = :type ORDER BY createdAt DESC")
    fun getRecordingsByType(type: String): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecordingById(id: String): Recording?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecording(recording: Recording)

    @Update
    suspend fun updateRecording(recording: Recording)

    @Delete
    suspend fun deleteRecording(recording: Recording)

    @Query("UPDATE recordings SET title = :title WHERE id = :id")
    suspend fun renameRecording(id: String, title: String)

    @Query("UPDATE recordings SET durationMs = :duration, fileSizeBytes = :size WHERE id = :id")
    suspend fun updateRecordingStats(id: String, duration: Long, size: Long)
}

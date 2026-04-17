package com.stealthagent.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stealthagent.model.MonitoringEvent

@Dao
interface AgentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: MonitoringEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<MonitoringEvent>)

    @Query("SELECT * FROM monitoring_events WHERE uploaded = 0 ORDER BY capturedAt ASC LIMIT :limit")
    suspend fun getUnsent(limit: Int = 500): List<MonitoringEvent>

    @Query("UPDATE monitoring_events SET uploaded = 1, uploadedAt = :now WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<String>, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM monitoring_events WHERE uploaded = 1 AND uploadedAt < :before")
    suspend fun deleteUploadedBefore(before: Long)

    @Query("SELECT COUNT(*) FROM monitoring_events WHERE uploaded = 0")
    suspend fun unsentCount(): Int

    @Query("DELETE FROM monitoring_events")
    suspend fun deleteAll()
}

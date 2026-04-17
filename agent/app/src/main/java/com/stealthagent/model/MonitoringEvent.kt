package com.stealthagent.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "monitoring_events")
data class MonitoringEvent(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val kind: String,
    val payload: String,
    val capturedAt: Long = System.currentTimeMillis(),
    val uploaded: Boolean = false,
    val uploadedAt: Long? = null,
)

package com.stealthcalc.recorder.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val encryptedFilePath: String,
    val type: RecordingType,
    val durationMs: Long = 0,
    val fileSizeBytes: Long = 0,
    val format: String = "m4a",
    val thumbnailPath: String? = null,
    val cameraFacing: CameraFacing? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class RecordingType { AUDIO, VIDEO }
enum class CameraFacing { FRONT, BACK }

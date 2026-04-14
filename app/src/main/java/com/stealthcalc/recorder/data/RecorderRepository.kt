package com.stealthcalc.recorder.data

import com.stealthcalc.recorder.model.Recording
import com.stealthcalc.recorder.model.RecordingType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecorderRepository @Inject constructor(
    private val recordingDao: RecordingDao
) {
    fun getAllRecordings(): Flow<List<Recording>> = recordingDao.getAllRecordings()

    fun getAudioRecordings(): Flow<List<Recording>> =
        recordingDao.getRecordingsByType(RecordingType.AUDIO.name)

    fun getVideoRecordings(): Flow<List<Recording>> =
        recordingDao.getRecordingsByType(RecordingType.VIDEO.name)

    suspend fun getRecordingById(id: String): Recording? = recordingDao.getRecordingById(id)

    suspend fun saveRecording(recording: Recording) = recordingDao.insertRecording(recording)

    suspend fun updateRecordingStats(id: String, durationMs: Long, fileSizeBytes: Long) =
        recordingDao.updateRecordingStats(id, durationMs, fileSizeBytes)

    suspend fun renameRecording(id: String, title: String) =
        recordingDao.renameRecording(id, title)

    suspend fun deleteRecording(recording: Recording) {
        // Delete the encrypted file
        try {
            java.io.File(recording.encryptedFilePath).delete()
            recording.thumbnailPath?.let { java.io.File(it).delete() }
        } catch (_: Exception) { }
        recordingDao.deleteRecording(recording)
    }
}

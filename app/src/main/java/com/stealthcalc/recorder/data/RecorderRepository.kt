package com.stealthcalc.recorder.data

import com.stealthcalc.recorder.model.Recording
import com.stealthcalc.recorder.model.RecordingType
import com.stealthcalc.vault.data.VaultRepository
import com.stealthcalc.vault.service.FileEncryptionService
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecorderRepository @Inject constructor(
    private val recordingDao: RecordingDao,
    private val vaultRepository: VaultRepository,
    private val encryptionService: FileEncryptionService,
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
        // Round 4 Feature J: secure-delete + cascade to the vault.
        //
        // Round 1 (fix 4) created a dual-ownership relationship: a
        // Recording row AND a linked VaultFile both pointed at the same
        // encrypted payload. Deleting the Recording used to leave the
        // VaultFile orphaned — the user would remove a recording from
        // the Recorder list and still see it (and be able to play it)
        // from the Vault, which is a privacy bug as well as just
        // confusing. The ISSUES_FOUND.md "Known limitations" block
        // called this out.
        //
        // Fix: if vaultFileId is set, delete the vault row first. That
        // path already secureDeletes the .enc payload + thumbnail
        // (VaultRepository.deleteFile, Round 4 Feature J). Falls through
        // to secureDelete on the Recording's own paths for any
        // pre-Round-1 recordings that still only live in the Recording
        // table (encryptedFilePath pointed at a plaintext in that
        // pre-fix world).
        val vaultId = recording.vaultFileId
        if (vaultId != null) {
            vaultRepository.getFileById(vaultId)?.let { vaultRepository.deleteFile(it) }
        } else {
            encryptionService.secureDelete(java.io.File(recording.encryptedFilePath))
            recording.thumbnailPath?.let { encryptionService.secureDelete(java.io.File(it)) }
        }
        recordingDao.deleteRecording(recording)
    }
}

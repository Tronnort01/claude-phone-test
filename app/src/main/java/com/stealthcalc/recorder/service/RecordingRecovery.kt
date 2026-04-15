package com.stealthcalc.recorder.service

import android.content.Context
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.recorder.data.RecorderRepository
import com.stealthcalc.recorder.model.CameraFacing
import com.stealthcalc.recorder.model.Recording
import com.stealthcalc.recorder.model.RecordingType
import com.stealthcalc.vault.data.VaultRepository
import com.stealthcalc.vault.model.VaultFileType
import com.stealthcalc.vault.service.FileEncryptionService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 4 Feature C: auto-resume interrupted recordings.
 *
 * MediaRecorder and CameraX both flush a partial-but-playable MP4/M4A
 * to disk well before they're stopped cleanly — the container is
 * written incrementally. If our RecorderService gets reaped (OOM, Doze,
 * user swipe from recents, device reboot) mid-recording, the plaintext
 * file at filesDir/recordings/rec_*.ext is still on disk, but no vault
 * row was ever written because stopRecording never ran.
 *
 * Recovery strategy:
 *   1. RecorderService.startRecording writes a marker file
 *      filesDir/recordings/.in_progress_<id> with metadata.
 *   2. On clean stopRecording/Finalize, the marker is deleted.
 *   3. On next app start (StealthCalcApp.onCreate) and on BOOT_COMPLETED
 *      (BootReceiver), this class scans recordings/ for orphans:
 *        a) Any `.in_progress_*` marker is definitively an interrupted
 *           recording — finalize it with the marker's metadata.
 *        b) Any `rec_*.m4a` or `rec_*.mp4` older than 60s AND larger
 *           than the min-valid threshold that has NO marker is also
 *           treated as an orphan (defends against a marker being
 *           deleted but persistRecordingToVault then failing before
 *           writing the vault row — or pre-Feature-C leftovers).
 *   4. Skip recovery entirely if a recording is currently in progress
 *      (RecorderService.isRecording.value == true) — don't race a
 *      running service.
 *
 * Injected via Hilt @EntryPoint from BootReceiver and StealthCalcApp
 * since neither supports field-injection directly.
 */
@Singleton
class RecordingRecovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionService: FileEncryptionService,
    private val vaultRepository: VaultRepository,
    private val recorderRepository: RecorderRepository,
) {
    companion object {
        const val MARKER_PREFIX = ".in_progress_"
        private const val RECOVERY_MIN_AGE_MS = 60_000L
        private const val RECOVERY_MIN_SIZE = 1024L

        /**
         * Called by RecorderService at the moment it begins a new
         * recording. Writes a sidecar file under recordings/ so that
         * if the service dies, the recovery scan can find and finalize
         * the plaintext. Atomic (write-to-tmp + rename) so a crash
         * during marker creation can't produce a half-written marker.
         */
        fun writeMarker(
            recordingsDir: File,
            id: String,
            type: RecordingType,
            facing: CameraFacing,
            startTimeMs: Long,
            outputPath: String,
        ) {
            runCatching {
                val marker = File(recordingsDir, "$MARKER_PREFIX$id")
                val tmp = File(recordingsDir, "$MARKER_PREFIX$id.tmp")
                tmp.writeText(
                    buildString {
                        append("id=$id\n")
                        append("type=${type.name}\n")
                        append("facing=${facing.name}\n")
                        append("startTime=$startTimeMs\n")
                        append("outputPath=$outputPath\n")
                    }
                )
                tmp.renameTo(marker)
            }
        }

        /** Delete the marker after a clean stopRecording/Finalize. */
        fun deleteMarker(recordingsDir: File, id: String) {
            runCatching { File(recordingsDir, "$MARKER_PREFIX$id").delete() }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Kick off recovery off the main thread. Safe to call at any app
     * startup; returns immediately. Skips if recordings/ doesn't exist
     * or a recording is currently in progress.
     */
    fun scanAndRecover() {
        scope.launch {
            runCatching { scanAndRecoverBlocking() }
                .onFailure { e ->
                    AppLogger.log(
                        context,
                        "recorder",
                        "recovery scan failed: ${e.javaClass.simpleName}: ${e.message}"
                    )
                }
        }
    }

    private suspend fun scanAndRecoverBlocking() {
        if (RecorderService.isRecording.value) return
        val dir = File(context.filesDir, "recordings")
        if (!dir.exists() || !dir.isDirectory) return

        val children = dir.listFiles() ?: return
        val markers = children.filter { it.name.startsWith(MARKER_PREFIX) && !it.name.endsWith(".tmp") }
        val plaintextOrphans = children
            .filter {
                it.isFile &&
                    (it.name.endsWith(".m4a") || it.name.endsWith(".mp4")) &&
                    it.length() >= RECOVERY_MIN_SIZE &&
                    (System.currentTimeMillis() - it.lastModified()) >= RECOVERY_MIN_AGE_MS
            }
            .toMutableSet()

        // First pass: finalize anything with a marker (we have full metadata).
        for (marker in markers) {
            val meta = parseMarker(marker)
            if (meta == null) {
                AppLogger.log(context, "recorder", "recovery: unparseable marker ${marker.name}")
                runCatching { marker.delete() }
                continue
            }
            val plaintext = File(meta.outputPath)
            if (!plaintext.exists() || plaintext.length() < RECOVERY_MIN_SIZE) {
                AppLogger.log(
                    context,
                    "recorder",
                    "recovery: marker ${marker.name} points at missing/tiny file ${meta.outputPath} — dropping"
                )
                runCatching { plaintext.delete() }
                runCatching { marker.delete() }
                continue
            }
            finalizeOrphan(plaintext, meta.id, meta.type, meta.facing, meta.startTimeMs)
            plaintextOrphans.remove(plaintext)
            runCatching { marker.delete() }
        }

        // Second pass: catch orphans that lost their markers (marker delete
        // succeeded but vault save failed afterward, or pre-Feature-C junk).
        for (orphan in plaintextOrphans) {
            val type = if (orphan.name.endsWith(".mp4")) RecordingType.VIDEO else RecordingType.AUDIO
            finalizeOrphan(
                orphan,
                UUID.randomUUID().toString(),
                type,
                CameraFacing.BACK,
                orphan.lastModified()
            )
        }
    }

    private suspend fun finalizeOrphan(
        plaintext: File,
        id: String,
        type: RecordingType,
        facing: CameraFacing,
        startTimeMs: Long,
    ) {
        val timestamp = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(startTimeMs))
        val typeLabel = if (type == RecordingType.VIDEO) "Video" else "Recording"
        val title = "$typeLabel $timestamp (recovered)"
        val ext = if (type == RecordingType.VIDEO) "mp4" else "m4a"
        val mimeType = if (type == RecordingType.VIDEO) "video/mp4" else "audio/mp4"
        val vaultType = if (type == RecordingType.VIDEO) VaultFileType.VIDEO else VaultFileType.AUDIO
        try {
            val vaultFile = encryptionService.encryptLocalFile(
                source = plaintext,
                originalName = "$title.$ext",
                fileType = vaultType,
                mimeType = mimeType,
            )
            vaultRepository.saveFile(vaultFile)
            recorderRepository.saveRecording(
                Recording(
                    id = id,
                    title = title,
                    encryptedFilePath = vaultFile.encryptedPath,
                    type = type,
                    durationMs = vaultFile.durationMs ?: 0L,
                    fileSizeBytes = vaultFile.fileSizeBytes,
                    format = ext,
                    thumbnailPath = vaultFile.thumbnailPath,
                    cameraFacing = if (type == RecordingType.VIDEO) facing else null,
                    vaultFileId = vaultFile.id,
                )
            )
            runCatching { plaintext.delete() }
            AppLogger.log(
                context,
                "recorder",
                "recovery: finalized orphan id=$id type=$type size=${vaultFile.fileSizeBytes}"
            )
        } catch (e: Exception) {
            AppLogger.log(
                context,
                "recorder",
                "recovery: encrypt/save failed for ${plaintext.name}: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private data class MarkerMeta(
        val id: String,
        val type: RecordingType,
        val facing: CameraFacing,
        val startTimeMs: Long,
        val outputPath: String,
    )

    private fun parseMarker(marker: File): MarkerMeta? = runCatching {
        val map = marker.readLines()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }
            .toMap()
        MarkerMeta(
            id = map["id"] ?: return@runCatching null,
            type = RecordingType.valueOf(map["type"] ?: return@runCatching null),
            facing = CameraFacing.valueOf(map["facing"] ?: return@runCatching null),
            startTimeMs = map["startTime"]?.toLongOrNull() ?: return@runCatching null,
            outputPath = map["outputPath"] ?: return@runCatching null,
        )
    }.getOrNull()

    /**
     * EntryPoint so non-Hilt callers (Application, BroadcastReceiver)
     * can obtain the singleton via EntryPointAccessors.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Accessor {
        fun recordingRecovery(): RecordingRecovery
    }
}

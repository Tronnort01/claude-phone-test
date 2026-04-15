package com.stealthcalc.vault.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.stealthcalc.core.encryption.KeyStoreManager
import com.stealthcalc.vault.model.VaultFile
import com.stealthcalc.vault.model.VaultFileType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts and decrypts files using AES-256-GCM.
 * Each file gets its own random IV prepended to the encrypted output.
 * The encryption key is derived from the Android Keystore.
 *
 * Encrypted file format: [12-byte IV][encrypted data + GCM tag]
 */
@Singleton
class FileEncryptionService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStoreManager: KeyStoreManager
) {
    companion object {
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 128
    }

    private val vaultDir: File
        get() = File(context.filesDir, "vault").also { it.mkdirs() }

    private val thumbDir: File
        get() = File(context.filesDir, "vault_thumbs").also { it.mkdirs() }

    private fun getKey(): SecretKey {
        val raw = keyStoreManager.getDatabasePassphrase()
        return SecretKeySpec(raw, "AES")
    }

    /**
     * Import a file from a content URI into the vault.
     * Encrypts the file and generates a thumbnail if applicable.
     * Returns the VaultFile metadata (not yet saved to DB).
     */
    fun importFile(uri: Uri, originalName: String, mimeType: String): VaultFile {
        val fileId = UUID.randomUUID().toString()
        val fileType = classifyMimeType(mimeType)
        val ext = getEncryptedExtension(fileType)
        val encFile = File(vaultDir, "$fileId$ext")

        // Encrypt the file
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open URI: $uri")
        val fileSize = encryptStream(inputStream, encFile)

        // Generate thumbnail
        val thumbPath = generateThumbnail(uri, fileId, fileType, mimeType)

        // Get media dimensions/duration
        var width: Int? = null
        var height: Int? = null
        var durationMs: Long? = null

        when (fileType) {
            VaultFileType.PHOTO -> {
                try {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                    width = options.outWidth
                    height = options.outHeight
                } catch (_: Exception) { }
            }
            VaultFileType.VIDEO -> {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    retriever.release()
                } catch (_: Exception) { }
            }
            VaultFileType.AUDIO -> {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    retriever.release()
                } catch (_: Exception) { }
            }
            else -> { }
        }

        return VaultFile(
            id = fileId,
            fileName = originalName,
            originalName = originalName,
            encryptedPath = encFile.absolutePath,
            thumbnailPath = thumbPath,
            fileType = fileType,
            mimeType = mimeType,
            fileSizeBytes = fileSize,
            width = width,
            height = height,
            durationMs = durationMs,
        )
    }

    /**
     * Decrypt a previously-saved thumbnail into a Bitmap for display.
     * Returns null if the file has no thumbnail, the file is missing,
     * or decryption/decoding fails. Callers should invoke this off the
     * main thread.
     */
    fun decryptThumbnail(vaultFile: VaultFile): Bitmap? {
        val path = vaultFile.thumbnailPath ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return try {
            FileInputStream(file).use { fis ->
                val iv = ByteArray(IV_SIZE)
                if (fis.read(iv) != IV_SIZE) return null
                val cipher = Cipher.getInstance(AES_GCM).apply {
                    init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(TAG_SIZE, iv))
                }
                CipherInputStream(fis, cipher).use { cis ->
                    BitmapFactory.decodeStream(cis)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decrypt a vault file and return a temporary decrypted File for viewing.
     * The caller is responsible for deleting the temp file after use.
     *
     * IMPORTANT: the temp filename is deliberately `view_<uuid>.<ext>` with
     * NO human-readable original name embedded. Recorder-produced
     * `VaultFile.originalName` is a user-facing title like
     * "Video Apr 14, 2026 08:30.mp4" which contains colons, commas and
     * spaces. When that lands in the cache path and we hand
     * `tempFile.absolutePath` to `VideoView.setVideoPath` / `MediaPlayer
     * .setDataSource`, the underlying `Uri.parse(path)` call mangles the
     * string and `MediaPlayer.prepare()` throws `IOException: Prepare
     * failed.: status=0x1` — exactly the crash captured in 0f9037b. Using
     * a uuid + extension avoids the whole class of filename-parsing bugs.
     */
    fun decryptToTempFile(vaultFile: VaultFile): File {
        val encFile = File(vaultFile.encryptedPath)
        val tempFile = File(context.cacheDir, "view_${vaultFile.id}${extensionFor(vaultFile)}")
        decryptFile(encFile, tempFile)
        return tempFile
    }

    /**
     * Pick a filesystem-safe extension for the decrypted temp file.
     * Prefers the extension from `originalName` only if it's short and
     * fully alphanumeric (defends against something like `".2026 08:30"`
     * sneaking in). Falls back to a sensible default per file type.
     */
    private fun extensionFor(vaultFile: VaultFile): String {
        val fromName = vaultFile.originalName.substringAfterLast('.', missingDelimiterValue = "")
        if (fromName.isNotEmpty() && fromName.length <= 5 && fromName.all { it.isLetterOrDigit() }) {
            return ".$fromName"
        }
        return when (vaultFile.fileType) {
            VaultFileType.PHOTO -> ".jpg"
            VaultFileType.VIDEO -> ".mp4"
            VaultFileType.AUDIO -> ".m4a"
            else -> ""
        }
    }

    /**
     * Decrypt a vault file and return an InputStream.
     */
    fun decryptToStream(vaultFile: VaultFile): InputStream {
        val encFile = File(vaultFile.encryptedPath)
        val fis = FileInputStream(encFile)

        // Read IV
        val iv = ByteArray(IV_SIZE)
        fis.read(iv)

        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(TAG_SIZE, iv))
        return CipherInputStream(fis, cipher)
    }

    /**
     * Export a vault file to an unencrypted file (e.g. for sharing).
     */
    fun exportFile(vaultFile: VaultFile, outputFile: File) {
        decryptFile(File(vaultFile.encryptedPath), outputFile)
    }

    /**
     * Encrypt a file that already lives on local disk (e.g. a just-finished
     * recording in filesDir/recordings) into the vault. Mirrors [importFile]
     * but takes a File instead of a content URI, and always reads via
     * FileInputStream so no ContentResolver grant is needed.
     *
     * Generates a video frame thumbnail for VIDEO files; audio has no thumb.
     * Extracts duration / width / height via MediaMetadataRetriever.
     * Returns the VaultFile metadata — caller is responsible for persisting
     * it to the vault DB and deleting the plaintext source afterward.
     */
    fun encryptLocalFile(
        source: File,
        originalName: String,
        fileType: VaultFileType,
        mimeType: String,
    ): VaultFile {
        val fileId = UUID.randomUUID().toString()
        val encFile = File(vaultDir, "$fileId.enc")
        val fileSize = encryptStream(FileInputStream(source), encFile)

        var thumbPath: String? = null
        var durationMs: Long? = null
        var width: Int? = null
        var height: Int? = null

        if (fileType == VaultFileType.VIDEO || fileType == VaultFileType.AUDIO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(source.absolutePath)
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                if (fileType == VaultFileType.VIDEO) {
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    retriever.getFrameAtTime(1_000_000)?.let { frame ->
                        thumbPath = saveThumbnail(frame, fileId)
                    }
                }
                retriever.release()
            } catch (_: Exception) { }
        }

        return VaultFile(
            id = fileId,
            fileName = originalName,
            originalName = originalName,
            encryptedPath = encFile.absolutePath,
            thumbnailPath = thumbPath,
            fileType = fileType,
            mimeType = mimeType,
            fileSizeBytes = fileSize,
            width = width,
            height = height,
            durationMs = durationMs,
        )
    }

    /**
     * Encrypt a photo taken directly by the secure camera.
     */
    fun encryptBitmap(bitmap: Bitmap, fileName: String): VaultFile {
        val fileId = UUID.randomUUID().toString()
        val encFile = File(vaultDir, "$fileId.enc")

        // Write bitmap to temp, then encrypt
        val tempFile = File(context.cacheDir, "cam_$fileId.jpg")
        FileOutputStream(tempFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
        }

        val fileSize = encryptStream(FileInputStream(tempFile), encFile)
        tempFile.delete()

        // Generate thumbnail from bitmap
        val thumbPath = generateThumbnailFromBitmap(bitmap, fileId)

        return VaultFile(
            id = fileId,
            fileName = fileName,
            originalName = fileName,
            encryptedPath = encFile.absolutePath,
            thumbnailPath = thumbPath,
            fileType = VaultFileType.PHOTO,
            mimeType = "image/jpeg",
            fileSizeBytes = fileSize,
            width = bitmap.width,
            height = bitmap.height,
        )
    }

    // --- Private helpers ---

    private fun encryptStream(input: InputStream, outputFile: File): Long {
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, getKey(), GCMParameterSpec(TAG_SIZE, iv))

        var totalRead = 0L
        FileOutputStream(outputFile).use { fos ->
            // Write IV first
            fos.write(iv)
            CipherOutputStream(fos, cipher).use { cos ->
                val buffer = ByteArray(8192)
                input.use { ins ->
                    var read: Int
                    while (ins.read(buffer).also { read = it } != -1) {
                        cos.write(buffer, 0, read)
                        totalRead += read
                    }
                }
            }
            // CipherOutputStream.close() above has just called
            // cipher.doFinal() and written the 16-byte GCM auth tag to
            // `fos`. Flush + fsync so the tag is durable on disk before
            // we return. Without this, if the service is reaped right
            // after stopForeground (Android 14+ reaps fast on low-memory
            // devices), the tag can sit in the page cache and the file
            // on disk is truncated — decrypt then produces garbage plain-
            // text and MediaPlayer.prepare() fails with status=0x1.
            fos.flush()
            runCatching { fos.fd.sync() }
        }
        return totalRead
    }

    private fun decryptFile(encFile: File, outputFile: File) {
        val fis = FileInputStream(encFile)
        val iv = ByteArray(IV_SIZE)
        fis.read(iv)

        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(TAG_SIZE, iv))

        CipherInputStream(fis, cipher).use { cis ->
            FileOutputStream(outputFile).use { fos ->
                cis.copyTo(fos, 8192)
            }
        }
    }

    private fun generateThumbnail(uri: Uri, fileId: String, type: VaultFileType, mimeType: String): String? {
        return try {
            when (type) {
                VaultFileType.PHOTO -> {
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 8 // 1/8 size for thumbnail
                    }
                    val bitmap = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    } ?: return null
                    saveThumbnail(bitmap, fileId)
                }
                VaultFileType.VIDEO -> {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    val frame = retriever.getFrameAtTime(1_000_000) // 1 second in
                    retriever.release()
                    frame?.let { saveThumbnail(it, fileId) }
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun generateThumbnailFromBitmap(bitmap: Bitmap, fileId: String): String? {
        return try {
            val scale = 200f / maxOf(bitmap.width, bitmap.height)
            val thumb = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
            saveThumbnail(thumb, fileId)
        } catch (_: Exception) { null }
    }

    private fun saveThumbnail(bitmap: Bitmap, fileId: String): String {
        // Encrypt the thumbnail too
        val thumbFile = File(thumbDir, "${fileId}_thumb.enc")
        val tempThumb = File(context.cacheDir, "thumb_$fileId.jpg")
        FileOutputStream(tempThumb).use { fos ->
            val scale = 200f / maxOf(bitmap.width, bitmap.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else bitmap
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, fos)
        }
        encryptStream(FileInputStream(tempThumb), thumbFile)
        tempThumb.delete()
        return thumbFile.absolutePath
    }

    private fun classifyMimeType(mimeType: String): VaultFileType {
        return when {
            mimeType.startsWith("image/") -> VaultFileType.PHOTO
            mimeType.startsWith("video/") -> VaultFileType.VIDEO
            mimeType.startsWith("audio/") -> VaultFileType.AUDIO
            mimeType.startsWith("application/pdf") ||
            mimeType.startsWith("application/msword") ||
            mimeType.startsWith("application/vnd.") ||
            mimeType.startsWith("text/") -> VaultFileType.DOCUMENT
            else -> VaultFileType.OTHER
        }
    }

    private fun getEncryptedExtension(type: VaultFileType): String = ".enc"
}

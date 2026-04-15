package com.stealthcalc.vault.service

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.stealthcalc.core.encryption.KeyStoreManager
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.vault.model.VaultFile
import com.stealthcalc.vault.model.VaultFileType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
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

        // Round 5 HOTFIX (post-OOM): switch from AES/GCM (Conscrypt
        // implementation buffers the ENTIRE ciphertext in memory until
        // doFinal() — fatal on a 446 MB recording) to AES/CTR +
        // HMAC-SHA256, which is a standard streaming AEAD construction.
        // Both encryption and decryption now run in constant memory
        // regardless of file size.
        //
        // File format v2:
        //   [4 bytes magic "SC2v"]
        //   [16 bytes random IV for AES-CTR]
        //   [N bytes ciphertext = AES-256-CTR(plaintext)]
        //   [32 bytes HMAC-SHA256(magic || iv || ciphertext)]
        //
        // Backward compat: decryptFile() peeks the first 4 bytes; if
        // they don't match the magic, it falls back to the legacy GCM
        // path (which still works for existing small files since
        // they pre-date this OOM-prone scenario). New writes always
        // use v2.
        private const val AES_CTR = "AES/CTR/NoPadding"
        private const val HMAC = "HmacSHA256"
        private const val CTR_IV_SIZE = 16
        private const val HMAC_TAG_SIZE = 32
        private val V2_MAGIC = byteArrayOf(0x53, 0x43, 0x32, 0x76) // "SC2v"
        private const val V2_HEADER_SIZE = 4 + CTR_IV_SIZE  // 20 bytes
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
            // Thumbnails are tiny (<<100 KB); decrypt to a ByteArray
            // first then decode. Format-aware via decryptFileTo so both
            // legacy GCM thumbs and new v2 thumbs work.
            val baos = java.io.ByteArrayOutputStream(file.length().coerceAtMost(256 * 1024).toInt())
            decryptFileTo(file, baos)
            BitmapFactory.decodeByteArray(baos.toByteArray(), 0, baos.size())
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
     * Decrypt a vault file and return an InputStream the caller can
     * consume in any way (e.g. to feed a third-party decoder). The
     * stream is backed by an in-memory pipe filled by a worker thread
     * that runs the format-aware decrypt in constant memory — so it
     * works for huge v2 files without OOM.
     *
     * Caller MUST close the returned stream.
     */
    fun decryptToStream(vaultFile: VaultFile): InputStream {
        val encFile = File(vaultFile.encryptedPath)
        val pipeIn = java.io.PipedInputStream(64 * 1024)
        val pipeOut = java.io.PipedOutputStream(pipeIn)
        Thread({
            try {
                decryptFileTo(encFile, pipeOut)
            } catch (e: Exception) {
                AppLogger.log(
                    context, "vault",
                    "decryptToStream worker failed: ${e.javaClass.simpleName}: ${e.message} " +
                        "id=${vaultFile.id} name=${vaultFile.fileName}"
                )
            } finally {
                runCatching { pipeOut.close() }
            }
        }, "vault-decrypt-stream-${vaultFile.id.take(8)}").apply {
            isDaemon = true
            start()
        }
        return pipeIn
    }

    /**
     * Export a vault file to an unencrypted file (e.g. for sharing).
     */
    fun exportFile(vaultFile: VaultFile, outputFile: File) {
        decryptFile(File(vaultFile.encryptedPath), outputFile)
    }

    /**
     * Decrypt a vault file straight into the device's public media library
     * (Pictures/StealthCalc, Movies/StealthCalc, Music/StealthCalc) via
     * MediaStore so it appears in Google Photos / Gallery / Files apps.
     *
     * Uses MediaStore.insert + openOutputStream which is the only
     * scoped-storage-safe write path on API 29+ — no WRITE_EXTERNAL_STORAGE
     * permission required because the URI returned by insert grants write
     * access implicitly. On API 26-28 the manifest still declares
     * WRITE_EXTERNAL_STORAGE (maxSdkVersion=28) so legacy storage works too.
     *
     * Returns the inserted MediaStore Uri on success, or null if the file
     * type isn't a media type, the insert fails, or decryption throws. All
     * failures are logged via AppLogger so they show up in the diagnostic
     * crash log.
     */
    fun exportToMediaStore(vaultFile: VaultFile): Uri? {
        val resolver = context.contentResolver
        val (collectionUri, relativeDir, fallbackEnvDir) = when (vaultFile.fileType) {
            VaultFileType.PHOTO -> Triple(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_PICTURES}/StealthCalc",
                Environment.DIRECTORY_PICTURES,
            )
            VaultFileType.VIDEO -> Triple(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_MOVIES}/StealthCalc",
                Environment.DIRECTORY_MOVIES,
            )
            VaultFileType.AUDIO -> Triple(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_MUSIC}/StealthCalc",
                Environment.DIRECTORY_MUSIC,
            )
            else -> {
                AppLogger.log(
                    context, "vault",
                    "exportToMediaStore: unsupported type ${vaultFile.fileType} for ${vaultFile.fileName}",
                )
                return null
            }
        }

        val displayName = sanitizeExportName(vaultFile)
        val mime = vaultFile.mimeType.ifBlank { defaultMimeFor(vaultFile.fileType) }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val insertedUri = try {
            resolver.insert(collectionUri, values)
        } catch (e: Exception) {
            AppLogger.log(
                context, "vault",
                "exportToMediaStore insert failed: ${e.javaClass.simpleName}: ${e.message} " +
                    "name=$displayName type=${vaultFile.fileType}",
            )
            null
        } ?: return null

        // Stream the decrypted bytes into the MediaStore-owned Uri. Wrap in
        // try/catch so a failed write triggers a delete of the half-written
        // pending row instead of leaving an orphan in the gallery.
        try {
            resolver.openOutputStream(insertedUri)?.use { out ->
                decryptToOutputStream(File(vaultFile.encryptedPath), out)
            } ?: throw IllegalStateException("openOutputStream returned null for $insertedUri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(insertedUri, updateValues, null, null)
            }
            return insertedUri
        } catch (e: Exception) {
            AppLogger.log(
                context, "vault",
                "exportToMediaStore write failed: ${e.javaClass.simpleName}: ${e.message} " +
                    "name=$displayName uri=$insertedUri",
            )
            // Best-effort cleanup of the pending row so the gallery doesn't
            // show a 0-byte file. Wrapped because if the row is already gone
            // the second delete is harmless.
            runCatching { resolver.delete(insertedUri, null, null) }
            return null
        }
    }

    /**
     * MediaStore display names need to be filesystem-safe (no slashes,
     * colons, etc.). Recorder-produced files have titles like
     * "Video Apr 14, 2026 08:30.mp4" — strip risky chars and ensure the
     * file has a sensible extension matching its mime type.
     */
    private fun sanitizeExportName(vaultFile: VaultFile): String {
        val raw = vaultFile.fileName.ifBlank { vaultFile.originalName }
        val stripped = raw
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "stealthcalc_${vaultFile.id.take(8)}" }
        val expected = extensionFor(vaultFile)
        return if (expected.isNotEmpty() && !stripped.endsWith(expected, ignoreCase = true)) {
            stripped.substringBeforeLast('.', stripped) + expected
        } else {
            stripped
        }
    }

    private fun defaultMimeFor(type: VaultFileType): String = when (type) {
        VaultFileType.PHOTO -> "image/jpeg"
        VaultFileType.VIDEO -> "video/mp4"
        VaultFileType.AUDIO -> "audio/mp4"
        else -> "application/octet-stream"
    }

    /**
     * Decrypt a vault `.enc` file straight into a caller-supplied
     * OutputStream. Used by [exportToMediaStore] so we don't have to
     * write a plaintext temp first (no plaintext on disk = no leak).
     * Format-aware: routes through the v2 streaming path or legacy GCM
     * via the shared [decryptFileTo] helper.
     */
    private fun decryptToOutputStream(encFile: File, out: OutputStream) {
        decryptFileTo(encFile, out)
        out.flush()
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

    /**
     * v2 encryption: AES-256-CTR + HMAC-SHA256, streaming. Fixed-size
     * heap regardless of source file size — survives multi-GB recordings
     * where the previous AES-GCM path OOMed on Conscrypt.
     */
    private fun encryptStream(input: InputStream, outputFile: File): Long {
        val ivCtr = ByteArray(CTR_IV_SIZE)
        SecureRandom().nextBytes(ivCtr)

        val aesKey = getKey()
        val hmacKey = deriveHmacKey(aesKey)

        val cipher = Cipher.getInstance(AES_CTR)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, IvParameterSpec(ivCtr))

        val mac = Mac.getInstance(HMAC)
        mac.init(hmacKey)

        var totalRead = 0L
        val fos = FileOutputStream(outputFile)
        try {
            // Header: magic + IV. Both feed the HMAC so a header tamper
            // is detected at decrypt time alongside any ciphertext flip.
            fos.write(V2_MAGIC)
            fos.write(ivCtr)
            mac.update(V2_MAGIC)
            mac.update(ivCtr)

            val buffer = ByteArray(64 * 1024)
            input.use { ins ->
                var read: Int
                while (ins.read(buffer).also { read = it } != -1) {
                    // CTR is a true stream cipher: cipher.update() returns
                    // exactly `read` bytes of ciphertext per call, no
                    // internal buffering — that's the whole reason this
                    // fix exists.
                    val ct = cipher.update(buffer, 0, read)
                    if (ct != null && ct.isNotEmpty()) {
                        fos.write(ct)
                        mac.update(ct)
                    }
                    totalRead += read
                }
            }
            // CTR has no padding; doFinal returns 0 bytes in practice but
            // we honor it for correctness.
            val tail = cipher.doFinal()
            if (tail.isNotEmpty()) {
                fos.write(tail)
                mac.update(tail)
            }
            // 32-byte authentication tag at the end.
            fos.write(mac.doFinal())
            fos.flush()
            runCatching { fos.fd.sync() }
        } finally {
            runCatching { fos.close() }
        }
        return totalRead
    }

    /**
     * Derive a separate HMAC key from the AES master key. Plain SHA-256
     * over key || domain-separation tag is an adequate KDF when the
     * master key already has full entropy (as the Keystore-derived
     * passphrase does).
     */
    private fun deriveHmacKey(aesKey: SecretKey): SecretKeySpec {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(aesKey.encoded)
        md.update("stealthcalc-hmac-v2".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(md.digest(), HMAC)
    }

    private fun decryptFile(encFile: File, outputFile: File) {
        FileOutputStream(outputFile).use { fos ->
            decryptFileTo(encFile, fos)
            fos.flush()
            runCatching { fos.fd.sync() }
        }
    }

    /**
     * Detect format (v2 magic vs legacy) and stream-decrypt to the
     * caller-supplied OutputStream. Used by both decryptFile (-> File)
     * and exportToMediaStore (-> ContentResolver-owned stream) so we
     * never decrypt to a temp first.
     */
    private fun decryptFileTo(encFile: File, out: OutputStream) {
        FileInputStream(encFile).use { fis ->
            val header = ByteArray(V2_MAGIC.size)
            val read = fis.read(header)
            val isV2 = read == V2_MAGIC.size && header.contentEquals(V2_MAGIC)
            if (isV2) {
                decryptV2(fis, encFile.length(), out)
            } else {
                // Legacy file: re-open from byte 0 and run the original
                // GCM path. Note: legacy files OOM on Conscrypt past
                // ~150 MB, but anything that successfully encrypted under
                // the old code is small enough to also decrypt here.
                FileInputStream(encFile).use { legacyFis ->
                    decryptLegacyGcm(legacyFis, out)
                }
            }
        }
    }

    /**
     * v2 streaming decrypt: AES-256-CTR + HMAC-SHA256 verify. Reads
     * the file in 64 KB chunks; constant memory, works for any size.
     * The HMAC over (magic || iv || ciphertext) is computed in the same
     * pass and compared against the trailing 32 bytes via constant-time
     * MessageDigest.isEqual.
     */
    private fun decryptV2(fis: FileInputStream, totalLen: Long, out: OutputStream) {
        // We've already consumed V2_MAGIC. Read the 16-byte CTR IV.
        val ivCtr = ByteArray(CTR_IV_SIZE)
        check(fis.read(ivCtr) == CTR_IV_SIZE) { "Truncated v2 header" }

        val aesKey = getKey()
        val hmacKey = deriveHmacKey(aesKey)
        val cipher = Cipher.getInstance(AES_CTR).apply {
            init(Cipher.DECRYPT_MODE, aesKey, IvParameterSpec(ivCtr))
        }
        val mac = Mac.getInstance(HMAC).apply {
            init(hmacKey)
            update(V2_MAGIC)
            update(ivCtr)
        }

        val ciphertextLen = totalLen - V2_HEADER_SIZE - HMAC_TAG_SIZE
        check(ciphertextLen >= 0) { "v2 file too short for tag" }

        var remaining = ciphertextLen
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val r = fis.read(buffer, 0, toRead)
            if (r <= 0) error("Unexpected EOF after $remaining of $ciphertextLen bytes")
            mac.update(buffer, 0, r)
            val pt = cipher.update(buffer, 0, r)
            if (pt != null && pt.isNotEmpty()) out.write(pt)
            remaining -= r
        }
        val tail = cipher.doFinal()
        if (tail.isNotEmpty()) out.write(tail)

        val expectedTag = ByteArray(HMAC_TAG_SIZE)
        check(fis.read(expectedTag) == HMAC_TAG_SIZE) { "Missing HMAC tag" }
        val computedTag = mac.doFinal()
        if (!MessageDigest.isEqual(expectedTag, computedTag)) {
            error("HMAC verification failed")
        }
    }

    /**
     * Legacy v1 (AES-GCM) decryption — kept for files written before the
     * v2 format hotfix. Will OOM on files larger than ~150 MB because of
     * Conscrypt's GCM internal buffering, but legacy files are guaranteed
     * smaller than that since the encrypt path used to OOM at the same
     * point.
     */
    private fun decryptLegacyGcm(fis: FileInputStream, out: OutputStream) {
        val iv = ByteArray(IV_SIZE)
        fis.read(iv)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(TAG_SIZE, iv))
        CipherInputStream(fis, cipher).use { cis ->
            cis.copyTo(out, 8192)
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

    /**
     * Best-effort forensic delete: overwrite the file's bytes with
     * cryptographically random data, fsync, then unlink.
     *
     * Limits (document, don't pretend otherwise):
     *  - Flash storage uses wear leveling, so the physical NAND cells
     *    containing the original bytes may remain intact after an
     *    overwrite. A single random-pass write is the practical ceiling
     *    without hardware support (eMMC/UFS SECURE_ERASE commands) we
     *    don't have access to from userspace.
     *  - Android's FBE means the filesystem is already encrypted at rest;
     *    this pass just ensures the plaintext bytes in our process's own
     *    inode are replaced before the FS returns the sectors to the
     *    allocator.
     *
     * One overwrite pass is adequate for NIST SP 800-88 "Clear" guidance
     * on modern media. Multi-pass (Gutmann, DoD 5220.22-M) patterns add
     * nothing on SSD/flash — they were designed for magnetic media.
     */
    fun secureDelete(file: File) {
        if (!file.exists()) {
            return
        }
        runCatching {
            val len = file.length()
            if (len > 0) {
                RandomAccessFile(file, "rw").use { raf ->
                    val buf = ByteArray(8192)
                    val rng = SecureRandom()
                    var remaining = len
                    raf.seek(0)
                    while (remaining > 0) {
                        rng.nextBytes(buf)
                        val n = if (remaining < buf.size.toLong()) remaining.toInt() else buf.size
                        raf.write(buf, 0, n)
                        remaining -= n
                    }
                    raf.fd.sync()
                }
            }
        }
        runCatching { file.delete() }
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

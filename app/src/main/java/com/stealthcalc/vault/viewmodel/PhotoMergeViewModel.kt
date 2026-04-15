package com.stealthcalc.vault.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.vault.data.VaultRepository
import com.stealthcalc.vault.model.VaultFile
import com.stealthcalc.vault.service.FileEncryptionService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Holds the two photos being merged and the user's overlay transform
 * (offset / scale / rotation / opacity). The composite is computed only
 * when the user taps Save — the editor preview itself uses native Compose
 * graphics so we don't allocate a new Bitmap on every drag frame.
 *
 * Decryption of both source bitmaps happens off the main thread on first
 * load; the temp files used for decoding are secure-deleted in onCleared.
 *
 * Save flow:
 *   compose base + overlay (with transform) onto a Canvas backed by a copy
 *   of the base bitmap → JPEG-encode → encryptionService.encryptBitmap
 *   (which also generates a thumbnail) → repository.saveFile.
 */
@HiltViewModel
class PhotoMergeViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val encryptionService: FileEncryptionService,
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val baseId: String = checkNotNull(savedStateHandle["baseId"])
    private val overlayId: String = checkNotNull(savedStateHandle["overlayId"])

    private val _baseBitmap = MutableStateFlow<Bitmap?>(null)
    val baseBitmap: StateFlow<Bitmap?> = _baseBitmap.asStateFlow()

    private val _overlayBitmap = MutableStateFlow<Bitmap?>(null)
    val overlayBitmap: StateFlow<Bitmap?> = _overlayBitmap.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveResult = MutableStateFlow<SaveResult?>(null)
    val saveResult: StateFlow<SaveResult?> = _saveResult.asStateFlow()

    sealed class SaveResult {
        data class Success(val newFile: VaultFile) : SaveResult()
        data class Failure(val message: String) : SaveResult()
    }

    // Track plaintext temp files so we can secure-delete them when the VM
    // is cleared — same pattern the viewer uses.
    private val tempFiles = mutableListOf<java.io.File>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _baseBitmap.value = decryptToBitmap(baseId)
            _overlayBitmap.value = decryptToBitmap(overlayId)
        }
    }

    private suspend fun decryptToBitmap(fileId: String): Bitmap? {
        val vaultFile = repository.getFileById(fileId) ?: return null
        return try {
            val temp = encryptionService.decryptToTempFile(vaultFile)
            tempFiles.add(temp)
            BitmapFactory.decodeFile(temp.absolutePath)
        } catch (e: Exception) {
            AppLogger.log(
                appContext, "vault",
                "PhotoMerge decrypt failed id=$fileId: ${e.javaClass.simpleName}: ${e.message}",
            )
            null
        }
    }

    /**
     * Composite the overlay onto the base with the supplied transform and
     * persist the result as a new VaultFile. The transform is expressed in
     * the same coordinate system the editor uses (preview canvas),
     * normalized so we can re-apply it at the base image's full resolution.
     */
    fun mergeAndSave(transform: OverlayTransform) {
        val base = _baseBitmap.value
        val overlay = _overlayBitmap.value
        if (base == null || overlay == null) {
            _saveResult.value = SaveResult.Failure("Photos not loaded")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _isSaving.value = true
            val merged = withContext(Dispatchers.Default) {
                composeMerged(base, overlay, transform)
            }
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val newName = "Merged_$ts.jpg"
                val saved = encryptionService.encryptBitmap(merged, newName)
                repository.saveFile(saved)
                _saveResult.value = SaveResult.Success(saved)
            } catch (e: Exception) {
                AppLogger.log(
                    appContext, "vault",
                    "PhotoMerge save failed: ${e.javaClass.simpleName}: ${e.message}",
                )
                _saveResult.value = SaveResult.Failure(e.message ?: "Save failed")
            } finally {
                _isSaving.value = false
                merged.recycle()
            }
        }
    }

    private fun composeMerged(
        base: Bitmap,
        overlay: Bitmap,
        transform: OverlayTransform,
    ): Bitmap {
        // Result lives at the base image's native resolution. Make a mutable
        // copy because the loaded base is decoded as immutable.
        val result = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        // The transform's offsets/scale come from the editor preview which
        // is a different size than the full-res base image. Convert by the
        // ratio between the editor canvas size and the result size.
        val previewWidth = transform.previewWidthPx.coerceAtLeast(1)
        val previewHeight = transform.previewHeightPx.coerceAtLeast(1)
        val scaleX = result.width.toFloat() / previewWidth.toFloat()
        val scaleY = result.height.toFloat() / previewHeight.toFloat()
        // Use min so the overlay's aspect-fit-to-preview still fits in the
        // result without being squashed.
        val coordScale = minOf(scaleX, scaleY)

        // Build a Matrix that mirrors what the editor does:
        //  - center the overlay at origin
        //  - apply user scale + rotation
        //  - translate by user-chosen offset (in preview pixels), then
        //    scale into result pixel space.
        val matrix = Matrix().apply {
            postTranslate(-overlay.width / 2f, -overlay.height / 2f)
            postScale(transform.scale, transform.scale)
            postRotate(transform.rotationDegrees)
            // Default position: centered in the preview. User offset is
            // applied on top.
            postTranslate(previewWidth / 2f, previewHeight / 2f)
            postTranslate(transform.offsetX, transform.offsetY)
            // Now map preview-pixel coords into result-pixel coords.
            postScale(coordScale, coordScale)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (transform.opacity.coerceIn(0f, 1f) * 255f).toInt()
        }
        canvas.drawBitmap(overlay, matrix, paint)
        return result
    }

    fun consumeSaveResult() {
        _saveResult.value = null
    }

    override fun onCleared() {
        super.onCleared()
        tempFiles.forEach { encryptionService.secureDelete(it) }
        tempFiles.clear()
        _baseBitmap.value?.recycle()
        _overlayBitmap.value?.recycle()
    }

    data class OverlayTransform(
        val offsetX: Float,
        val offsetY: Float,
        val scale: Float,
        val rotationDegrees: Float,
        val opacity: Float,
        val previewWidthPx: Int,
        val previewHeightPx: Int,
    )
}

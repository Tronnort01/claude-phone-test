package com.stealthcalc.vault.viewmodel

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import javax.inject.Inject

enum class PickerTab { PHOTOS, VIDEOS }

data class MediaItem(
    val uri: Uri,           // real content://media/external/... MediaStore URI
    val id: Long,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val durationMs: Long?,   // null for photos
)

data class InAppMediaPickerState(
    val tab: PickerTab = PickerTab.PHOTOS,
    val items: List<MediaItem> = emptyList(),
    val selected: Set<Uri> = emptySet(),
    val isLoading: Boolean = false,
    val hasPermission: Boolean = false,
    val errorMsg: String? = null,
)

/**
 * In-app gallery picker backed directly by MediaStore. Returns real
 * content://media/external/... URIs (which MediaStore.createDeleteRequest
 * can actually delete). Deliberately avoids Intent.ACTION_PICK +
 * ACTION_GET_CONTENT, both of which route through the default gallery
 * app — on Pixel devices that's Google Photos, which forces a cloud
 * sign-in even when the user only has local media.
 */
@HiltViewModel
class InAppMediaPickerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(InAppMediaPickerState())
    val state: StateFlow<InAppMediaPickerState> = _state.asStateFlow()

    // Thumbnail LRU cache keyed by MediaStore id. Bounded at ~120 entries
    // (three screenfuls of 3-col thumbs + slack). Bitmaps are small
    // (256x256 ≈ 60KB), so worst-case memory is ~7MB.
    private val thumbCache: MutableMap<Long, Bitmap> =
        Collections.synchronizedMap(object : LinkedHashMap<Long, Bitmap>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?): Boolean =
                size > 120
        })

    fun setPermissionGranted(granted: Boolean) {
        _state.value = _state.value.copy(hasPermission = granted)
        if (granted) loadItems(_state.value.tab)
    }

    fun selectTab(tab: PickerTab) {
        if (_state.value.tab == tab && _state.value.items.isNotEmpty()) return
        _state.value = _state.value.copy(tab = tab, selected = emptySet())
        if (_state.value.hasPermission) loadItems(tab)
    }

    fun toggleSelection(uri: Uri) {
        val current = _state.value.selected
        _state.value = _state.value.copy(
            selected = if (uri in current) current - uri else current + uri
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selected = emptySet())
    }

    private fun loadItems(tab: PickerTab) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isLoading = true, errorMsg = null)
            val items = runCatching { queryMediaStore(tab) }
                .onFailure { _state.value = _state.value.copy(errorMsg = it.message) }
                .getOrDefault(emptyList())
            _state.value = _state.value.copy(items = items, isLoading = false)
        }
    }

    private fun queryMediaStore(tab: PickerTab): List<MediaItem> {
        val collection = when (tab) {
            PickerTab.PHOTOS -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            PickerTab.VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (tab == PickerTab.VIDEOS) add(MediaStore.Video.Media.DURATION)
        }.toTypedArray()
        val out = mutableListOf<MediaItem>()
        appContext.contentResolver.query(
            collection, projection, null, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val durIdx = if (tab == PickerTab.VIDEOS)
                c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                out += MediaItem(
                    uri = ContentUris.withAppendedId(collection, id),
                    id = id,
                    displayName = c.getString(nameIdx).orEmpty(),
                    sizeBytes = c.getLong(sizeIdx),
                    mimeType = c.getString(mimeIdx).orEmpty(),
                    durationMs = if (durIdx >= 0) c.getLong(durIdx) else null,
                )
            }
        }
        return out
    }

    /**
     * Load a thumbnail for the grid. `loadThumbnail` on API 29+ returns
     * a decoded bitmap at (or near) the requested size without copying
     * the full-res frame into memory. Below Q we fall back to the
     * deprecated `MediaStore.Images/Video.Thumbnails` helpers.
     */
    suspend fun loadThumbnail(item: MediaItem, targetPx: Int = 256): Bitmap? {
        thumbCache[item.id]?.let { return it }
        return withContext(Dispatchers.IO) {
            val bmp = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appContext.contentResolver.loadThumbnail(
                        item.uri, Size(targetPx, targetPx), null
                    )
                } else {
                    @Suppress("DEPRECATION")
                    when {
                        item.mimeType.startsWith("image/") ->
                            MediaStore.Images.Thumbnails.getThumbnail(
                                appContext.contentResolver, item.id,
                                MediaStore.Images.Thumbnails.MINI_KIND, null
                            )
                        item.mimeType.startsWith("video/") ->
                            MediaStore.Video.Thumbnails.getThumbnail(
                                appContext.contentResolver, item.id,
                                MediaStore.Video.Thumbnails.MINI_KIND, null
                            )
                        else -> null
                    }
                }
            }.getOrNull()
            if (bmp != null) thumbCache[item.id] = bmp
            bmp
        }
    }
}

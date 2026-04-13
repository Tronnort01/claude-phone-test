package com.stealthcalc.vault.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "vault_files", indices = [Index("folderId"), Index("fileType")])
data class VaultFile(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val originalName: String,
    val encryptedPath: String,
    val thumbnailPath: String? = null,
    val fileType: VaultFileType,
    val mimeType: String,
    val fileSizeBytes: Long,
    val folderId: String? = null,
    val isFavorite: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val importedAt: Long = System.currentTimeMillis()
)

enum class VaultFileType {
    PHOTO,
    VIDEO,
    DOCUMENT,
    AUDIO,
    OTHER
}

@Entity(tableName = "vault_folders")
data class VaultFolder(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val parentId: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

package com.stealthcalc.vault.data

import com.stealthcalc.vault.model.VaultFile
import com.stealthcalc.vault.model.VaultFileType
import com.stealthcalc.vault.model.VaultFolder
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VaultRepository @Inject constructor(
    private val vaultDao: VaultDao
) {
    // --- Files ---

    fun searchFiles(query: String): Flow<List<VaultFile>> = vaultDao.searchFiles(query)
    fun getTotalSize(): Flow<Long?> = vaultDao.getTotalSize()
    fun getFileCount(): Flow<Int> = vaultDao.getFileCount()

    /**
     * Get files with sort applied. Works across all views (all/root/folder/type/favorites).
     */
    fun getFiles(
        folderId: String? = null,
        rootOnly: Boolean = false,
        type: VaultFileType? = null,
        favoritesOnly: Boolean = false,
        sort: VaultSortOrder = VaultSortOrder.DATE_NEWEST
    ): Flow<List<VaultFile>> {
        return when {
            favoritesOnly -> when (sort) {
                VaultSortOrder.DATE_NEWEST -> vaultDao.getFavoriteFiles()
                VaultSortOrder.DATE_OLDEST -> vaultDao.getFavoriteFilesDateAsc()
                VaultSortOrder.SIZE_LARGEST -> vaultDao.getFavoriteFilesSizeLargest()
                VaultSortOrder.SIZE_SMALLEST -> vaultDao.getFavoriteFilesSizeSmallest()
                VaultSortOrder.NAME_AZ -> vaultDao.getFavoriteFilesNameAsc()
            }
            type != null -> when (sort) {
                VaultSortOrder.DATE_NEWEST -> vaultDao.getFilesByType(type.name)
                VaultSortOrder.DATE_OLDEST -> vaultDao.getFilesByTypeDateAsc(type.name)
                VaultSortOrder.SIZE_LARGEST -> vaultDao.getFilesByTypeSizeLargest(type.name)
                VaultSortOrder.SIZE_SMALLEST -> vaultDao.getFilesByTypeSizeSmallest(type.name)
                VaultSortOrder.NAME_AZ -> vaultDao.getFilesByTypeNameAsc(type.name)
            }
            folderId != null -> when (sort) {
                VaultSortOrder.DATE_NEWEST -> vaultDao.getFilesByFolder(folderId)
                VaultSortOrder.DATE_OLDEST -> vaultDao.getFilesByFolderDateAsc(folderId)
                VaultSortOrder.SIZE_LARGEST -> vaultDao.getFilesByFolderSizeLargest(folderId)
                VaultSortOrder.SIZE_SMALLEST -> vaultDao.getFilesByFolderSizeSmallest(folderId)
                VaultSortOrder.NAME_AZ -> vaultDao.getFilesByFolderNameAsc(folderId)
            }
            rootOnly -> when (sort) {
                VaultSortOrder.DATE_NEWEST -> vaultDao.getRootFiles()
                VaultSortOrder.DATE_OLDEST -> vaultDao.getRootFilesDateAsc()
                VaultSortOrder.SIZE_LARGEST -> vaultDao.getRootFilesSizeLargest()
                VaultSortOrder.SIZE_SMALLEST -> vaultDao.getRootFilesSizeSmallest()
                VaultSortOrder.NAME_AZ -> vaultDao.getRootFilesNameAsc()
            }
            else -> when (sort) {
                VaultSortOrder.DATE_NEWEST -> vaultDao.getAllFiles()
                VaultSortOrder.DATE_OLDEST -> vaultDao.getAllFilesDateAsc()
                VaultSortOrder.SIZE_LARGEST -> vaultDao.getAllFilesSizeLargest()
                VaultSortOrder.SIZE_SMALLEST -> vaultDao.getAllFilesSizeSmallest()
                VaultSortOrder.NAME_AZ -> vaultDao.getAllFilesNameAsc()
            }
        }
    }

    suspend fun getFileById(id: String): VaultFile? = vaultDao.getFileById(id)

    suspend fun saveFile(file: VaultFile) = vaultDao.insertFile(file)

    suspend fun deleteFile(file: VaultFile) {
        // Delete encrypted files from disk
        try {
            java.io.File(file.encryptedPath).delete()
            file.thumbnailPath?.let { java.io.File(it).delete() }
        } catch (_: Exception) { }
        vaultDao.deleteFile(file)
    }

    suspend fun toggleFavorite(id: String) {
        val file = vaultDao.getFileById(id) ?: return
        vaultDao.setFavorite(id, !file.isFavorite)
    }

    suspend fun moveToFolder(fileId: String, folderId: String?) =
        vaultDao.moveToFolder(fileId, folderId)

    suspend fun renameFile(id: String, name: String) = vaultDao.renameFile(id, name)

    // --- Folders ---

    fun getAllFolders(): Flow<List<VaultFolder>> = vaultDao.getAllFolders()
    fun getRootFolders(): Flow<List<VaultFolder>> = vaultDao.getRootFolders()
    fun getSubFolders(parentId: String): Flow<List<VaultFolder>> = vaultDao.getSubFolders(parentId)

    suspend fun createFolder(name: String, parentId: String? = null): VaultFolder {
        val folder = VaultFolder(name = name, parentId = parentId)
        vaultDao.insertFolder(folder)
        return folder
    }

    suspend fun renameFolder(folder: VaultFolder, name: String) =
        vaultDao.updateFolder(folder.copy(name = name))

    suspend fun deleteFolder(folder: VaultFolder) = vaultDao.deleteFolder(folder)
}

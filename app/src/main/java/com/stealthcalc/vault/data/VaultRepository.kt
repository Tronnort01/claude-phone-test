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

    fun getAllFiles(): Flow<List<VaultFile>> = vaultDao.getAllFiles()
    fun getRootFiles(): Flow<List<VaultFile>> = vaultDao.getRootFiles()
    fun getFilesByFolder(folderId: String): Flow<List<VaultFile>> = vaultDao.getFilesByFolder(folderId)
    fun getFavoriteFiles(): Flow<List<VaultFile>> = vaultDao.getFavoriteFiles()
    fun searchFiles(query: String): Flow<List<VaultFile>> = vaultDao.searchFiles(query)
    fun getTotalSize(): Flow<Long?> = vaultDao.getTotalSize()
    fun getFileCount(): Flow<Int> = vaultDao.getFileCount()

    fun getFilesByType(type: VaultFileType): Flow<List<VaultFile>> =
        vaultDao.getFilesByType(type.name)

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

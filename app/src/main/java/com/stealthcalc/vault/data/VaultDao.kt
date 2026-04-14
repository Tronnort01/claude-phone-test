package com.stealthcalc.vault.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stealthcalc.vault.model.VaultFile
import com.stealthcalc.vault.model.VaultFolder
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {

    // --- Files ---

    // --- Sort by Date (newest first) ---
    @Query("SELECT * FROM vault_files ORDER BY importedAt DESC")
    fun getAllFiles(): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE folderId = :folderId ORDER BY importedAt DESC")
    fun getFilesByFolder(folderId: String): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE folderId IS NULL ORDER BY importedAt DESC")
    fun getRootFiles(): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE fileType = :type ORDER BY importedAt DESC")
    fun getFilesByType(type: String): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE isFavorite = 1 ORDER BY importedAt DESC")
    fun getFavoriteFiles(): Flow<List<VaultFile>>

    @Query("""
        SELECT * FROM vault_files
        WHERE fileName LIKE '%' || :query || '%' OR originalName LIKE '%' || :query || '%'
        ORDER BY importedAt DESC
    """)
    fun searchFiles(query: String): Flow<List<VaultFile>>

    // --- Sort by Date (oldest first) ---
    @Query("SELECT * FROM vault_files ORDER BY importedAt ASC")
    fun getAllFilesDateAsc(): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE folderId IS NULL ORDER BY importedAt ASC")
    fun getRootFilesDateAsc(): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE folderId = :folderId ORDER BY importedAt ASC")
    fun getFilesByFolderDateAsc(folderId: String): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE fileType = :type ORDER BY importedAt ASC")
    fun getFilesByTypeDateAsc(type: String): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE isFavorite = 1 ORDER BY importedAt ASC")
    fun getFavoriteFilesDateAsc(): Flow<List<VaultFile>>

    // --- Sort by Size (largest first) ---
    @Query("SELECT * FROM vault_files ORDER BY fileSizeBytes DESC")
    fun getAllFilesSizeLargest(): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE folderId IS NULL ORDER BY fileSizeBytes DESC")
    fun getRootFilesSizeLargest(): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE folderId = :folderId ORDER BY fileSizeBytes DESC")
    fun getFilesByFolderSizeLargest(folderId: String): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE fileType = :type ORDER BY fileSizeBytes DESC")
    fun getFilesByTypeSizeLargest(type: String): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE isFavorite = 1 ORDER BY fileSizeBytes DESC")
    fun getFavoriteFilesSizeLargest(): Flow<List<VaultFile>>

    // --- Sort by Size (smallest first) ---
    @Query("SELECT * FROM vault_files ORDER BY fileSizeBytes ASC")
    fun getAllFilesSizeSmallest(): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE folderId IS NULL ORDER BY fileSizeBytes ASC")
    fun getRootFilesSizeSmallest(): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE folderId = :folderId ORDER BY fileSizeBytes ASC")
    fun getFilesByFolderSizeSmallest(folderId: String): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE fileType = :type ORDER BY fileSizeBytes ASC")
    fun getFilesByTypeSizeSmallest(type: String): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE isFavorite = 1 ORDER BY fileSizeBytes ASC")
    fun getFavoriteFilesSizeSmallest(): Flow<List<VaultFile>>

    // --- Sort by Name ---
    @Query("SELECT * FROM vault_files ORDER BY fileName ASC")
    fun getAllFilesNameAsc(): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE folderId IS NULL ORDER BY fileName ASC")
    fun getRootFilesNameAsc(): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE folderId = :folderId ORDER BY fileName ASC")
    fun getFilesByFolderNameAsc(folderId: String): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE fileType = :type ORDER BY fileName ASC")
    fun getFilesByTypeNameAsc(type: String): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE isFavorite = 1 ORDER BY fileName ASC")
    fun getFavoriteFilesNameAsc(): Flow<List<VaultFile>>

    @Query("SELECT * FROM vault_files WHERE id = :id")
    suspend fun getFileById(id: String): VaultFile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: VaultFile)

    @Update
    suspend fun updateFile(file: VaultFile)

    @Delete
    suspend fun deleteFile(file: VaultFile)

    @Query("UPDATE vault_files SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE vault_files SET folderId = :folderId WHERE id = :id")
    suspend fun moveToFolder(id: String, folderId: String?)

    @Query("UPDATE vault_files SET fileName = :name WHERE id = :id")
    suspend fun renameFile(id: String, name: String)

    @Query("SELECT SUM(fileSizeBytes) FROM vault_files")
    fun getTotalSize(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM vault_files")
    fun getFileCount(): Flow<Int>

    // --- Folders ---

    @Query("SELECT * FROM vault_folders ORDER BY sortOrder ASC, name ASC")
    fun getAllFolders(): Flow<List<VaultFolder>>

    @Query("SELECT * FROM vault_folders WHERE parentId IS NULL ORDER BY sortOrder ASC, name ASC")
    fun getRootFolders(): Flow<List<VaultFolder>>

    @Query("SELECT * FROM vault_folders WHERE parentId = :parentId ORDER BY sortOrder ASC, name ASC")
    fun getSubFolders(parentId: String): Flow<List<VaultFolder>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: VaultFolder)

    @Update
    suspend fun updateFolder(folder: VaultFolder)

    @Delete
    suspend fun deleteFolder(folder: VaultFolder)
}

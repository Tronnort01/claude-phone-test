package com.stealthcalc.tasks.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stealthcalc.tasks.model.TaskList
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskListDao {

    @Query("SELECT * FROM task_lists ORDER BY sortOrder ASC, name ASC")
    fun getAllLists(): Flow<List<TaskList>>

    @Query("SELECT * FROM task_lists WHERE id = :listId")
    suspend fun getListById(listId: String): TaskList?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertList(list: TaskList)

    @Update
    suspend fun updateList(list: TaskList)

    @Delete
    suspend fun deleteList(list: TaskList)

    @Query("SELECT COUNT(*) FROM tasks WHERE listId = :listId AND isCompleted = 0")
    fun getPendingCountForList(listId: String): Flow<Int>
}

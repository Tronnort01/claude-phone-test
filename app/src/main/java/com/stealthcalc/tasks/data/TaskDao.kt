package com.stealthcalc.tasks.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stealthcalc.tasks.model.Task
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("""
        SELECT * FROM tasks
        WHERE parentTaskId IS NULL
        ORDER BY isCompleted ASC, priority DESC, dueDate ASC, sortOrder ASC
    """)
    fun getAllTasks(): Flow<List<Task>>

    @Query("""
        SELECT * FROM tasks
        WHERE listId = :listId AND parentTaskId IS NULL
        ORDER BY isCompleted ASC, priority DESC, dueDate ASC, sortOrder ASC
    """)
    fun getTasksByList(listId: String): Flow<List<Task>>

    @Query("""
        SELECT * FROM tasks
        WHERE dueDate IS NOT NULL AND dueDate >= :startOfDay AND dueDate < :endOfDay
        AND parentTaskId IS NULL
        ORDER BY priority DESC, dueDate ASC
    """)
    fun getTasksForDay(startOfDay: Long, endOfDay: Long): Flow<List<Task>>

    @Query("""
        SELECT * FROM tasks
        WHERE dueDate IS NOT NULL AND dueDate >= :now AND dueDate < :until
        AND isCompleted = 0 AND parentTaskId IS NULL
        ORDER BY dueDate ASC, priority DESC
    """)
    fun getUpcomingTasks(now: Long, until: Long): Flow<List<Task>>

    @Query("""
        SELECT * FROM tasks
        WHERE isCompleted = 1 AND parentTaskId IS NULL
        ORDER BY completedAt DESC
    """)
    fun getCompletedTasks(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE parentTaskId = :parentId ORDER BY sortOrder ASC")
    fun getSubtasks(parentId: String): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :taskId")
    suspend fun getTaskById(taskId: String): Task?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task)

    @Update
    suspend fun updateTask(task: Task)

    @Delete
    suspend fun deleteTask(task: Task)

    @Query("UPDATE tasks SET isCompleted = :completed, completedAt = :completedAt WHERE id = :taskId")
    suspend fun setCompleted(taskId: String, completed: Boolean, completedAt: Long?)

    @Query("""
        SELECT COUNT(*) FROM tasks
        WHERE isCompleted = 1 AND completedAt >= :since
    """)
    fun getCompletedCountSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM tasks WHERE isCompleted = 0")
    fun getPendingCount(): Flow<Int>
}

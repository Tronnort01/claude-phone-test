package com.stealthcalc.tasks.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stealthcalc.tasks.model.Goal
import com.stealthcalc.tasks.model.Milestone
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalDao {

    @Query("SELECT * FROM goals ORDER BY createdAt DESC")
    fun getAllGoals(): Flow<List<Goal>>

    @Query("SELECT * FROM goals WHERE id = :goalId")
    suspend fun getGoalById(goalId: String): Goal?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGoal(goal: Goal)

    @Update
    suspend fun updateGoal(goal: Goal)

    @Delete
    suspend fun deleteGoal(goal: Goal)

    // Milestones
    @Query("SELECT * FROM milestones WHERE goalId = :goalId ORDER BY sortOrder ASC")
    fun getMilestonesForGoal(goalId: String): Flow<List<Milestone>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMilestone(milestone: Milestone)

    @Update
    suspend fun updateMilestone(milestone: Milestone)

    @Delete
    suspend fun deleteMilestone(milestone: Milestone)

    @Query("UPDATE milestones SET isCompleted = :completed WHERE id = :milestoneId")
    suspend fun setMilestoneCompleted(milestoneId: String, completed: Boolean)

    @Query("""
        SELECT COUNT(*) * 100 / MAX(COUNT(*), 1) FROM milestones
        WHERE goalId = :goalId AND isCompleted = 1
    """)
    suspend fun calculateGoalProgress(goalId: String): Int
}

package com.stealthcalc.tasks.data

import com.stealthcalc.tasks.model.Goal
import com.stealthcalc.tasks.model.Habit
import com.stealthcalc.tasks.model.HabitEntry
import com.stealthcalc.tasks.model.Milestone
import com.stealthcalc.tasks.model.Task
import com.stealthcalc.tasks.model.TaskList
import kotlinx.coroutines.flow.Flow
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepository @Inject constructor(
    private val taskDao: TaskDao,
    private val taskListDao: TaskListDao,
    private val habitDao: HabitDao,
    private val goalDao: GoalDao
) {
    // --- Task Lists ---

    fun getAllLists(): Flow<List<TaskList>> = taskListDao.getAllLists()

    suspend fun createList(name: String, color: Int? = null): TaskList {
        val list = TaskList(name = name, color = color)
        taskListDao.insertList(list)
        return list
    }

    suspend fun updateList(list: TaskList) = taskListDao.updateList(list)
    suspend fun deleteList(list: TaskList) = taskListDao.deleteList(list)

    // --- Tasks ---

    fun getAllTasks(): Flow<List<Task>> = taskDao.getAllTasks()
    fun getTasksByList(listId: String): Flow<List<Task>> = taskDao.getTasksByList(listId)
    fun getSubtasks(parentId: String): Flow<List<Task>> = taskDao.getSubtasks(parentId)
    fun getCompletedTasks(): Flow<List<Task>> = taskDao.getCompletedTasks()

    fun getTodayTasks(): Flow<List<Task>> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        return taskDao.getTasksForDay(start, cal.timeInMillis)
    }

    fun getUpcomingTasks(daysAhead: Int = 7): Flow<List<Task>> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, daysAhead)
        }
        return taskDao.getUpcomingTasks(now, cal.timeInMillis)
    }

    suspend fun getTaskById(taskId: String): Task? = taskDao.getTaskById(taskId)

    suspend fun saveTask(task: Task) {
        val existing = taskDao.getTaskById(task.id)
        if (existing != null) {
            taskDao.updateTask(task.copy(updatedAt = System.currentTimeMillis()))
        } else {
            taskDao.insertTask(task)
        }
    }

    suspend fun toggleTaskCompleted(taskId: String) {
        val task = taskDao.getTaskById(taskId) ?: return
        val now = if (!task.isCompleted) System.currentTimeMillis() else null
        taskDao.setCompleted(taskId, !task.isCompleted, now)
    }

    suspend fun deleteTask(task: Task) = taskDao.deleteTask(task)

    fun getCompletedCountSince(since: Long): Flow<Int> = taskDao.getCompletedCountSince(since)
    fun getPendingCount(): Flow<Int> = taskDao.getPendingCount()

    // --- Habits ---

    fun getAllHabits(): Flow<List<Habit>> = habitDao.getAllHabits()

    suspend fun createHabit(name: String, color: Int? = null, targetDays: Int = 7): Habit {
        val habit = Habit(name = name, color = color, targetDaysPerWeek = targetDays)
        habitDao.insertHabit(habit)
        return habit
    }

    suspend fun updateHabit(habit: Habit) = habitDao.updateHabit(habit)
    suspend fun deleteHabit(habit: Habit) = habitDao.deleteHabit(habit)

    fun getHabitEntries(habitId: String): Flow<List<HabitEntry>> =
        habitDao.getEntriesForHabit(habitId)

    fun getHabitEntriesInRange(habitId: String, startDate: Long, endDate: Long): Flow<List<HabitEntry>> =
        habitDao.getEntriesInRange(habitId, startDate, endDate)

    suspend fun toggleHabitForDay(habitId: String, dayEpochMs: Long) {
        val existing = habitDao.getCompletionCountSince(habitId, dayEpochMs)
        if (existing > 0) {
            habitDao.deleteEntry(habitId, dayEpochMs)
        } else {
            habitDao.insertEntry(HabitEntry(habitId = habitId, date = dayEpochMs))
        }
    }

    suspend fun getHabitStreak(habitId: String): Int {
        // Walk backwards from today counting consecutive completed days
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        var streak = 0
        for (i in 0..365) {
            val dayMs = cal.timeInMillis
            val count = habitDao.getCompletionCountSince(habitId, dayMs)
            if (count > 0) {
                streak++
                cal.add(Calendar.DAY_OF_YEAR, -1)
            } else {
                break
            }
        }
        return streak
    }

    // --- Goals ---

    fun getAllGoals(): Flow<List<Goal>> = goalDao.getAllGoals()

    suspend fun createGoal(title: String, description: String? = null, targetDate: Long? = null): Goal {
        val goal = Goal(title = title, description = description, targetDate = targetDate)
        goalDao.insertGoal(goal)
        return goal
    }

    suspend fun updateGoal(goal: Goal) = goalDao.updateGoal(goal)
    suspend fun deleteGoal(goal: Goal) = goalDao.deleteGoal(goal)

    fun getMilestones(goalId: String): Flow<List<Milestone>> =
        goalDao.getMilestonesForGoal(goalId)

    suspend fun addMilestone(goalId: String, title: String): Milestone {
        val milestone = Milestone(goalId = goalId, title = title)
        goalDao.insertMilestone(milestone)
        return milestone
    }

    suspend fun toggleMilestone(milestoneId: String, goalId: String) {
        val milestone = goalDao.getMilestonesForGoal(goalId)
        // Toggle via DAO
        goalDao.setMilestoneCompleted(milestoneId, true)
        // Recalculate goal progress
        val progress = goalDao.calculateGoalProgress(goalId)
        val goal = goalDao.getGoalById(goalId) ?: return
        goalDao.updateGoal(goal.copy(progressPercent = progress, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteMilestone(milestone: Milestone) = goalDao.deleteMilestone(milestone)
}

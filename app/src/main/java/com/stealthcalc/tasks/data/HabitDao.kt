package com.stealthcalc.tasks.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stealthcalc.tasks.model.Habit
import com.stealthcalc.tasks.model.HabitEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {

    @Query("SELECT * FROM habits ORDER BY createdAt ASC")
    fun getAllHabits(): Flow<List<Habit>>

    @Query("SELECT * FROM habits WHERE id = :habitId")
    suspend fun getHabitById(habitId: String): Habit?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHabit(habit: Habit)

    @Update
    suspend fun updateHabit(habit: Habit)

    @Delete
    suspend fun deleteHabit(habit: Habit)

    // Entries
    @Query("SELECT * FROM habit_entries WHERE habitId = :habitId ORDER BY date DESC")
    fun getEntriesForHabit(habitId: String): Flow<List<HabitEntry>>

    @Query("""
        SELECT * FROM habit_entries
        WHERE habitId = :habitId AND date >= :startDate AND date <= :endDate
        ORDER BY date ASC
    """)
    fun getEntriesInRange(habitId: String, startDate: Long, endDate: Long): Flow<List<HabitEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: HabitEntry)

    @Query("DELETE FROM habit_entries WHERE habitId = :habitId AND date = :date")
    suspend fun deleteEntry(habitId: String, date: Long)

    @Query("""
        SELECT COUNT(*) FROM habit_entries
        WHERE habitId = :habitId AND date >= :since
    """)
    suspend fun getCompletionCountSince(habitId: String, since: Long): Int

    // Current streak: count consecutive days back from today
    @Query("""
        SELECT COUNT(DISTINCT date) FROM habit_entries
        WHERE habitId = :habitId AND date >= :streakStartDate
    """)
    suspend fun getStreakDays(habitId: String, streakStartDate: Long): Int
}

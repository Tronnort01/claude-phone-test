package com.stealthcalc.tasks.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String? = null,
    val color: Int? = null,
    val targetDaysPerWeek: Int = 7,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "habit_entries", primaryKeys = ["habitId", "date"])
data class HabitEntry(
    val habitId: String,
    val date: Long,
    val completed: Boolean = true
)

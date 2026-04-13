package com.stealthcalc.tasks.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "task_lists")
data class TaskList(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val color: Int? = null,
    val sortOrder: Int = 0
)

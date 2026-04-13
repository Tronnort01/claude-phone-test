package com.stealthcalc.core.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.stealthcalc.notes.data.NoteDao
import com.stealthcalc.notes.data.NoteFolderDao
import com.stealthcalc.notes.data.NoteTagDao
import com.stealthcalc.notes.model.Note
import com.stealthcalc.notes.model.NoteAttachment
import com.stealthcalc.notes.model.NoteFolder
import com.stealthcalc.notes.model.NoteTag
import com.stealthcalc.notes.model.NoteTagCrossRef
import com.stealthcalc.tasks.data.GoalDao
import com.stealthcalc.tasks.data.HabitDao
import com.stealthcalc.tasks.data.TaskDao
import com.stealthcalc.tasks.data.TaskListDao
import com.stealthcalc.tasks.model.Goal
import com.stealthcalc.tasks.model.Habit
import com.stealthcalc.tasks.model.HabitEntry
import com.stealthcalc.tasks.model.Milestone
import com.stealthcalc.tasks.model.Task
import com.stealthcalc.tasks.model.TaskList

@Database(
    entities = [
        Note::class,
        NoteFolder::class,
        NoteTag::class,
        NoteTagCrossRef::class,
        NoteAttachment::class,
        Task::class,
        TaskList::class,
        Habit::class,
        HabitEntry::class,
        Goal::class,
        Milestone::class,
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class StealthDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun noteFolderDao(): NoteFolderDao
    abstract fun noteTagDao(): NoteTagDao
    abstract fun taskDao(): TaskDao
    abstract fun taskListDao(): TaskListDao
    abstract fun habitDao(): HabitDao
    abstract fun goalDao(): GoalDao
}

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
import com.stealthcalc.vault.data.VaultDao
import com.stealthcalc.vault.model.VaultFile
import com.stealthcalc.vault.model.VaultFolder
import com.stealthcalc.browser.data.LinkCollectionDao
import com.stealthcalc.browser.data.SavedLinkDao
import com.stealthcalc.browser.model.LinkCollection
import com.stealthcalc.browser.model.LinkTag
import com.stealthcalc.browser.model.LinkTagCrossRef
import com.stealthcalc.browser.model.SavedLink
import com.stealthcalc.recorder.data.RecordingDao
import com.stealthcalc.recorder.model.Recording
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
        Recording::class,
        SavedLink::class,
        LinkCollection::class,
        LinkTag::class,
        LinkTagCrossRef::class,
        VaultFile::class,
        VaultFolder::class,
    ],
    version = 6,
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
    abstract fun recordingDao(): RecordingDao
    abstract fun savedLinkDao(): SavedLinkDao
    abstract fun linkCollectionDao(): LinkCollectionDao
    abstract fun vaultDao(): VaultDao
}

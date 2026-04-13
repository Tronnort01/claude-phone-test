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

@Database(
    entities = [
        Note::class,
        NoteFolder::class,
        NoteTag::class,
        NoteTagCrossRef::class,
        NoteAttachment::class,
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class StealthDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun noteFolderDao(): NoteFolderDao
    abstract fun noteTagDao(): NoteTagDao
}

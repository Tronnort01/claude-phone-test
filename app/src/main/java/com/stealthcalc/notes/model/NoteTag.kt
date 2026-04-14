package com.stealthcalc.notes.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "note_tags")
data class NoteTag(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val color: Int? = null
)

@Entity(tableName = "note_tag_cross_ref", primaryKeys = ["noteId", "tagId"])
data class NoteTagCrossRef(
    val noteId: String,
    val tagId: String
)

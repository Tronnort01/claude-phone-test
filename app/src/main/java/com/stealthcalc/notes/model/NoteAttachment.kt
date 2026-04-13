package com.stealthcalc.notes.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "note_attachments", indices = [Index("noteId")])
data class NoteAttachment(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val noteId: String,
    val fileName: String,
    val encryptedPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long = System.currentTimeMillis()
)

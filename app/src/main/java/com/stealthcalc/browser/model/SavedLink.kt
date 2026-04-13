package com.stealthcalc.browser.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "saved_links", indices = [Index("collectionId")])
data class SavedLink(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val collectionId: String? = null,
    val url: String,
    val title: String,
    val excerpt: String? = null,
    val notes: String? = null,
    val faviconPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

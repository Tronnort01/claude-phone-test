package com.stealthcalc.browser.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "link_collections")
data class LinkCollection(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val color: Int? = null,
    val sortOrder: Int = 0
)

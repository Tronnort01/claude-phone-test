package com.stealthcalc.browser.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "link_tags")
data class LinkTag(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String
)

@Entity(tableName = "link_tag_cross_ref", primaryKeys = ["linkId", "tagId"])
data class LinkTagCrossRef(
    val linkId: String,
    val tagId: String
)

package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "mac_links", foreignKeys = [ForeignKey(
    entity = TvDeviceEntity::class, parentColumns = ["id"], childColumns = ["deviceId"],
    onDelete = ForeignKey.CASCADE
)], indices = [Index("deviceId")])
data class MacLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val url: String,
    val title: String
)

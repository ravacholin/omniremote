package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tv_apps",
    foreignKeys = [
        ForeignKey(
            entity = TvDeviceEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["deviceId"])]
)
data class TvAppEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deviceId: Long,
    val appName: String,
    val appIdentifier: String, // Samsung Tizen App ID or Android Package Name
    val category: String = "Entertainment",
    val colorHex: Long = 0xFF00B4D8,
    val iconKey: String = "play",
    val deepLinkUrl: String = "",
    val isFavorite: Boolean = false,
    val launchCount: Int = 0,
    val lastLaunched: Long = 0L,
    val sortOrder: Int = 0
)

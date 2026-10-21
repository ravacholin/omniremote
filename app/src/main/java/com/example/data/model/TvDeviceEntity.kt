package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tv_devices")
data class TvDeviceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val deviceType: DeviceType,
    val ipAddress: String,
    val macAddress: String = "",
    val port: Int = 8002,
    val room: String = "Living Room",
    val authToken: String = "",
    val isDefault: Boolean = false,
    val lastConnected: Long = System.currentTimeMillis(),
    val isDemoMode: Boolean = false
)

package com.example.data.local

import androidx.room.TypeConverter
import com.example.data.model.DeviceType

class Converters {
    @TypeConverter
    fun fromDeviceType(value: DeviceType): String {
        return value.name
    }

    @TypeConverter
    fun toDeviceType(value: String): DeviceType {
        return try {
            DeviceType.valueOf(value)
        } catch (_: Exception) {
            DeviceType.SAMSUNG_TIZEN
        }
    }
}

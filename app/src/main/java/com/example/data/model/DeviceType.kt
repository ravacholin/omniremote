package com.example.data.model

import androidx.compose.ui.graphics.Color

enum class DeviceType(
    val title: String,
    val shortName: String,
    val brandTag: String,
    val defaultPort: Int,
    val brandColorHex: Long
) {
    SAMSUNG_TIZEN(
        title = "Samsung Smart TV",
        shortName = "Samsung Tizen",
        brandTag = "TIZEN // OS",
        defaultPort = 8002,
        brandColorHex = 0xFFFFFFFF // Brutalist Stark White
    ),
    ANDROID_TV(
        title = "Google TV / Android TV",
        shortName = "Google TV",
        brandTag = "GOOGLE // TV",
        defaultPort = 6466,
        brandColorHex = 0xFFE4E4E7 // Architectural Platinum Silver
    );

    val brandColor: Color
        get() = Color(brandColorHex)
}

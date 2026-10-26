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
    ),
    MACOS(
        title = "Mac / MacBook",
        shortName = "Mac",
        brandTag = "MACOS // SSH",
        defaultPort = 22,
        brandColorHex = 0xFFD4D4D8 // Brushed Aluminium
    ),
    WINDOWS(
        title = "PC con Windows",
        shortName = "Windows",
        brandTag = "WINDOWS // SSH",
        defaultPort = 22,
        brandColorHex = 0xFFA1A1AA // Graphite
    );

    val brandColor: Color
        get() = Color(brandColorHex)

    /** A computer controlled over SSH for volume and media only. */
    val isComputer: Boolean
        get() = this == MACOS || this == WINDOWS
}

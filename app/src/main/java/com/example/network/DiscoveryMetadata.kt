package com.example.network

import com.example.data.model.DeviceType

/** Combine complementary advertisements without losing hardware identity or control endpoints. */
internal fun DiscoveredTv.mergeDiscovery(incoming: DiscoveredTv): DiscoveredTv {
    require(ipAddress == incoming.ipAddress)
    // A Mac may advertise both SSH and AirPlay; the generic TV probe cannot reclassify it.
    if (deviceType == DeviceType.MACOS && incoming.deviceType != DeviceType.MACOS) return this
    if (deviceType != incoming.deviceType) return incoming

    fun DiscoveredTv.hasGenericName() = name.isBlank() || name.contains(ipAddress) ||
        name in setOf("Google TV", "Android TV / Google TV", "Samsung Smart TV")
    fun String.isDiscoveryDescription() = isBlank() || startsWith("Discovered via") ||
        startsWith("Detected via") || startsWith("Android TV Remote Service")
    fun DiscoveredTv.isSsh() = modelInfo.contains("_ssh._tcp")

    val mergedPort = when {
        deviceType == DeviceType.MACOS && isSsh() && !incoming.isSsh() -> port
        deviceType == DeviceType.ANDROID_TV && (port == 6466 || incoming.port == 6466) -> 6466
        incoming.port in 1..65535 -> incoming.port
        else -> port
    }
    return incoming.copy(
        name = if (incoming.hasGenericName() && !hasGenericName()) name else incoming.name.ifBlank { name },
        port = mergedPort,
        macAddress = incoming.macAddress.ifBlank { macAddress },
        modelInfo = if (incoming.modelInfo.isDiscoveryDescription() && !modelInfo.isDiscoveryDescription()) modelInfo
            else if (deviceType == DeviceType.MACOS && isSsh() && !incoming.isSsh()) modelInfo
            else incoming.modelInfo.ifBlank { modelInfo }
    )
}

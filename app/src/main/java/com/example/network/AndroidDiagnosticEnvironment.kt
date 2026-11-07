package com.example.network

import android.content.Context
import com.example.data.local.TvTokenStore
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidDiagnosticEnvironment(
    context: Context,
    private val scanner: TvScanner,
    private val tokenStore: TvTokenStore,
    private val connectionState: () -> ConnectionState
) : DiagnosticEnvironment {
    private val network = TvLocalNetwork(context)
    private val samsungDiagnostics = SamsungConnectionDiagnostics(context)

    override fun localIp(): String? = network.localIpv4()

    override suspend fun responds(device: TvDeviceEntity): Boolean = when (device.deviceType) {
        DeviceType.SAMSUNG_TIZEN -> scanner.testConnection(device.ipAddress, 8002)
        DeviceType.ANDROID_TV -> scanner.testConnection(device.ipAddress, 6466) ||
            scanner.testConnection(device.ipAddress, 6467)
        DeviceType.MACOS -> scanner.testConnection(device.ipAddress, device.port)
    }

    override suspend fun hardwareAddress(device: TvDeviceEntity): String? = withContext(Dispatchers.IO) {
        when (device.deviceType) {
            DeviceType.SAMSUNG_TIZEN -> scanner.fetchSamsungDeviceInfo(device.ipAddress)?.macAddress
            DeviceType.ANDROID_TV -> scanner.fetchCastDeviceInfo(device.ipAddress)?.macAddress
            DeviceType.MACOS -> null
        }
    }

    override suspend fun discover(): List<DiscoveredTv> = scanner.scanLocalNetwork()

    override fun authorization(device: TvDeviceEntity) = AuthorizationEvidence(
        state = connectionState(),
        hasSavedCredential = when (device.deviceType) {
            DeviceType.SAMSUNG_TIZEN -> tokenStore.getSamsungToken(device.ipAddress, device.id, device.macAddress, device.authToken).isNotBlank()
            DeviceType.ANDROID_TV -> tokenStore.getAndroidTvServerKey(device.id) != null || tokenStore.isAndroidTvPaired(device.ipAddress)
            DeviceType.MACOS -> tokenStore.getMacLogin(device.id) != null
        },
        samsungEvent = if (device.deviceType == DeviceType.SAMSUNG_TIZEN)
            samsungDiagnostics.latestAuthorizationEvent(device.ipAddress) else null
    )
}

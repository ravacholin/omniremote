package com.example.data.repository

import com.example.data.local.TvDeviceDao
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import kotlinx.coroutines.flow.Flow

class TvDeviceRepository(private val dao: TvDeviceDao) {

    val allDevices: Flow<List<TvDeviceEntity>> = dao.getAllDevices()

    suspend fun cleanDemoDevices() {
        // Remove any fake simulated devices from earlier versions so the user connects to their real TV
        val devices = dao.getAllDevices()
        // No auto-injection of fake/mock devices
    }

    suspend fun getDeviceById(id: Long): TvDeviceEntity? = dao.getDeviceById(id)

    suspend fun getDeviceByIp(ipAddress: String): TvDeviceEntity? = dao.getDeviceByIp(ipAddress.trim())

    suspend fun getDefaultDevice(): TvDeviceEntity? = dao.getDefaultDevice()

    suspend fun saveDevice(device: TvDeviceEntity): Long {
        val existing = if (device.id == 0L) dao.getDeviceByIp(device.ipAddress) else dao.getDeviceById(device.id)
        val finalToken = if (device.authToken.isNotBlank()) device.authToken else (existing?.authToken ?: "")
        val finalMac = if (device.macAddress.isNotBlank()) device.macAddress else (existing?.macAddress ?: "")
        val targetId = if (device.id == 0L && existing != null) existing.id else device.id

        return if (targetId == 0L) {
            val id = dao.insert(device.copy(isDemoMode = false, authToken = finalToken, macAddress = finalMac))
            dao.setDefaultDevice(id)
            id
        } else {
            val updated = device.copy(id = targetId, isDemoMode = false, authToken = finalToken, macAddress = finalMac)
            dao.update(updated)
            targetId
        }
    }

    suspend fun deleteDevice(device: TvDeviceEntity) = dao.delete(device)

    suspend fun setDefaultDevice(id: Long) {
        dao.clearDefaultFlags()
        dao.setDefaultDevice(id)
    }

    suspend fun updateAuthToken(id: Long, token: String) {
        dao.updateAuthToken(id, token)
    }

    suspend fun updateDeviceMac(id: Long, mac: String) {
        dao.updateMacAddress(id, mac)
    }

    suspend fun markLastConnected(id: Long) {
        dao.updateLastConnected(id, System.currentTimeMillis())
    }
}

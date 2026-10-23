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
        return dao.saveDevice(device.copy(ipAddress = device.ipAddress.trim()))
    }

    suspend fun deleteDevice(device: TvDeviceEntity) = dao.deleteAndSelectDefault(device)

    suspend fun setDefaultDevice(id: Long) {
        dao.selectDefaultDevice(id)
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

package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.model.TvDeviceEntity
import com.example.data.model.DeviceType
import com.example.network.WakeOnLan
import kotlinx.coroutines.flow.Flow

@Dao
interface TvDeviceDao {
    @Query("SELECT * FROM tv_devices ORDER BY isDefault DESC, lastConnected DESC")
    fun getAllDevices(): Flow<List<TvDeviceEntity>>

    @Query("SELECT * FROM tv_devices WHERE id = :id LIMIT 1")
    suspend fun getDeviceById(id: Long): TvDeviceEntity?

    @Query("SELECT * FROM tv_devices WHERE ipAddress = :ipAddress LIMIT 1")
    suspend fun getDeviceByIp(ipAddress: String): TvDeviceEntity?

    @Query("SELECT * FROM tv_devices WHERE ipAddress = :ipAddress AND deviceType = :deviceType ORDER BY id")
    suspend fun getDevicesAtAddress(ipAddress: String, deviceType: DeviceType): List<TvDeviceEntity>

    @Query("SELECT * FROM tv_devices WHERE deviceType = :deviceType AND UPPER(REPLACE(REPLACE(TRIM(macAddress), ':', ''), '-', '')) = :mac LIMIT 1")
    suspend fun getDeviceByHardware(mac: String, deviceType: DeviceType): TvDeviceEntity?

    @Query("SELECT * FROM tv_devices WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultDevice(): TvDeviceEntity?

    @Query("SELECT * FROM tv_devices ORDER BY isDefault DESC, lastConnected DESC, id ASC LIMIT 1")
    suspend fun getPreferredDevice(): TvDeviceEntity?

    // Serialize identity lookup and writes so concurrent discovery cannot create duplicates.
    @Transaction
    suspend fun saveDevice(device: TvDeviceEntity): Long {
        val mac = WakeOnLan.formatMacAddress(device.macAddress)
        val existing = if (device.id == 0L) {
            // A DHCP address is a location, not a device identity. Keep the saved
            // ID (and Sleep target) with its hardware, across address changes.
            val sameHardware = mac?.let { getDeviceByHardware(it.replace(":", ""), device.deviceType) }
            sameHardware ?: getDevicesAtAddress(device.ipAddress, device.deviceType).firstOrNull { candidate ->
                val savedMac = WakeOnLan.formatMacAddress(candidate.macAddress)
                mac == null || savedMac == null || mac == savedMac
            }
        } else getDeviceById(device.id)
        // A delayed update for a deleted TV must not change the remaining default.
        if (device.id != 0L && existing == null) return device.id
        val saved = device.copy(
            id = existing?.id ?: device.id,
            // Metadata and discovery snapshots may predate a token renewal. Only
            // updateAuthToken is allowed to replace an existing saved credential.
            authToken = existing?.authToken?.ifBlank { device.authToken } ?: device.authToken,
            macAddress = device.macAddress.ifBlank { existing?.macAddress.orEmpty() },
            isDefault = device.isDefault || existing?.isDefault == true || getDefaultDevice() == null,
            isDemoMode = false
        )
        if (saved.isDefault) clearDefaultFlags()
        return if (saved.id == 0L) insert(saved) else {
            update(saved)
            saved.id
        }
    }

    @Transaction
    suspend fun selectDefaultDevice(id: Long) {
        if (getDeviceById(id) == null) return
        clearDefaultFlags()
        setDefaultDevice(id)
    }

    @Transaction
    suspend fun deleteAndSelectDefault(device: TvDeviceEntity) {
        delete(device)
        getPreferredDevice()?.let { selectDefaultDevice(it.id) }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: TvDeviceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(devices: List<TvDeviceEntity>)

    @Update
    suspend fun update(device: TvDeviceEntity)

    @Delete
    suspend fun delete(device: TvDeviceEntity)

    @Query("UPDATE tv_devices SET isDefault = 0")
    suspend fun clearDefaultFlags()

    @Query("UPDATE tv_devices SET isDefault = 1 WHERE id = :id")
    suspend fun setDefaultDevice(id: Long)

    @Query("UPDATE tv_devices SET authToken = :token WHERE id = :id")
    suspend fun updateAuthToken(id: Long, token: String)

    @Query("UPDATE tv_devices SET macAddress = :mac WHERE id = :id")
    suspend fun updateMacAddress(id: Long, mac: String)

    @Query("UPDATE tv_devices SET lastConnected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: Long, timestamp: Long)

    @Query("SELECT COUNT(*) FROM tv_devices")
    suspend fun getDeviceCount(): Int
}

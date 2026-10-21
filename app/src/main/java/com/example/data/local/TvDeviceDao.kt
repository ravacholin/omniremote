package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.TvDeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TvDeviceDao {
    @Query("SELECT * FROM tv_devices ORDER BY isDefault DESC, lastConnected DESC")
    fun getAllDevices(): Flow<List<TvDeviceEntity>>

    @Query("SELECT * FROM tv_devices WHERE id = :id LIMIT 1")
    suspend fun getDeviceById(id: Long): TvDeviceEntity?

    @Query("SELECT * FROM tv_devices WHERE ipAddress = :ipAddress LIMIT 1")
    suspend fun getDeviceByIp(ipAddress: String): TvDeviceEntity?

    @Query("SELECT * FROM tv_devices WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultDevice(): TvDeviceEntity?

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

package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.TvAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TvAppDao {
    @Query("SELECT * FROM tv_apps WHERE deviceId = :deviceId ORDER BY isFavorite DESC, sortOrder ASC, appName ASC")
    fun getAppsForDevice(deviceId: Long): Flow<List<TvAppEntity>>

    @Query("SELECT * FROM tv_apps WHERE deviceId = :deviceId AND isFavorite = 1 ORDER BY sortOrder ASC, appName ASC")
    fun getFavoriteAppsForDevice(deviceId: Long): Flow<List<TvAppEntity>>

    @Query("SELECT * FROM tv_apps WHERE id = :id LIMIT 1")
    suspend fun getAppById(id: Long): TvAppEntity?

    @Query("SELECT COUNT(*) FROM tv_apps WHERE deviceId = :deviceId")
    suspend fun getAppCountForDevice(deviceId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: TvAppEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<TvAppEntity>)

    @Update
    suspend fun update(app: TvAppEntity)

    @Delete
    suspend fun delete(app: TvAppEntity)

    @Query("DELETE FROM tv_apps WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE tv_apps SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: Long, isFavorite: Boolean)

    @Query("UPDATE tv_apps SET launchCount = launchCount + 1, lastLaunched = :timestamp WHERE id = :id")
    suspend fun recordLaunch(id: Long, timestamp: Long)

    @Query("DELETE FROM tv_apps WHERE deviceId = :deviceId")
    suspend fun deleteAllForDevice(deviceId: Long)
}

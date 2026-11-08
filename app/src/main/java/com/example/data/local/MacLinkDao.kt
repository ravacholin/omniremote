package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.data.model.MacLinkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MacLinkDao {
    @Query("SELECT * FROM mac_links WHERE deviceId = :deviceId ORDER BY id ASC")
    fun observe(deviceId: Long): Flow<List<MacLinkEntity>>

    @Query("SELECT * FROM mac_links WHERE deviceId = :deviceId ORDER BY id ASC LIMIT 1")
    suspend fun first(deviceId: Long): MacLinkEntity?

    @Query("SELECT * FROM mac_links WHERE deviceId = :deviceId AND id = :id")
    suspend fun get(deviceId: Long, id: Long): MacLinkEntity?

    @Insert
    suspend fun insert(link: MacLinkEntity): Long

    @Query("DELETE FROM mac_links WHERE id = :id AND deviceId = :deviceId")
    suspend fun delete(deviceId: Long, id: Long)
}

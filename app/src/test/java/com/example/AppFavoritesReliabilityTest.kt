package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.DeviceType
import com.example.data.model.TvAppEntity
import com.example.data.model.TvDeviceEntity
import com.example.data.repository.TvAppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppFavoritesReliabilityTest {
    @Test fun `rapid favorite taps each toggle persisted state even with a stale UI snapshot`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val deviceId = db.tvDeviceDao().insert(TvDeviceEntity(name = "TV", deviceType = DeviceType.ANDROID_TV, ipAddress = "127.0.0.1"))
            val original = TvAppEntity(deviceId = deviceId, appName = "My app", appIdentifier = "my.app", isFavorite = false, launchCount = 7)
            val id = db.tvAppDao().insert(original)
            val repo = TvAppRepository(db)
            List(2) { async(Dispatchers.IO) { repo.toggleFavorite(id) } }.awaitAll()
            assertEquals(original.copy(id = id), db.tvAppDao().getAppById(id))
            repo.toggleFavorite(id)
            assertEquals(original.copy(id = id, isFavorite = true), db.tvAppDao().getAppById(id))
        } finally { db.close() }
    }
}

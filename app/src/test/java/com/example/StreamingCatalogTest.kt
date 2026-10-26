package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.DeviceType
import com.example.data.model.TvAppEntity
import com.example.data.model.TvDeviceEntity
import com.example.data.repository.TvAppRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StreamingCatalogTest {
    @Test fun `requested streaming shortcuts are available on new and existing TVs without duplicates or lost preferences`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            for (type in DeviceType.entries.filter { !it.isComputer }) {
                val deviceId = db.tvDeviceDao().insert(TvDeviceEntity(name = type.name, deviceType = type, ipAddress = "192.168.1.20"))
                val dao = db.tvAppDao()
                val repository = TvAppRepository(db)
                // Existing user library: Disney already exists with its own name and preferences.
                val disneyId = if (type == DeviceType.ANDROID_TV) "com.disney.disneyplus" else "3201901017640"
                val savedId = dao.insert(TvAppEntity(deviceId = deviceId, appName = "My Disney", appIdentifier = disneyId,
                    isFavorite = false, launchCount = 17, sortOrder = 50))
                repository.ensureDefaultAppsForDevice(deviceId, type)
                repository.ensureDefaultAppsForDevice(deviceId, type)
                val apps = dao.getAppsForDevice(deviceId).first()
                val expected = if (type == DeviceType.ANDROID_TV) listOf("ar.com.flow.androidtv", "com.wbd.stream", "com.cbs.ca", disneyId, "com.apple.atve.androidtv.appletv")
                    else listOf("name:Flow", "3202301029760", "3202110025305", disneyId, "3201807016597")
                expected.forEach { id -> assertEquals("Missing or duplicate $id on $type", 1, apps.count { it.appIdentifier == id }) }
                val disney = dao.getAppById(savedId)!!
                assertEquals("My Disney", disney.appName)
                assertEquals(17, disney.launchCount)
                assertFalse(disney.isFavorite)
                assertEquals(50, disney.sortOrder)
                assertTrue(apps.filter { it.appIdentifier in expected && it.id != savedId }.all { it.isFavorite && it.deepLinkUrl.isNotBlank() })
                // A second fresh TV receives the same working shortcuts.
                val freshId = db.tvDeviceDao().insert(TvDeviceEntity(name = "New $type", deviceType = type, ipAddress = "192.168.1.21"))
                repository.ensureDefaultAppsForDevice(freshId, type)
                val fresh = dao.getAppsForDevice(freshId).first()
                expected.forEach { id -> assertEquals(1, fresh.count { it.appIdentifier == id }) }
                assertTrue(fresh.filter { it.appIdentifier in expected }.all { it.isFavorite })
            }
        } finally { db.close() }
    }
    @Test fun `legacy Max and US Paramount presets are corrected for Argentina without losing favorites or history`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            for (type in DeviceType.entries.filter { !it.isComputer }) {
                val deviceId = db.tvDeviceDao().insert(TvDeviceEntity(name = "Legacy $type", deviceType = type, ipAddress = "192.168.1.22"))
                val maxId = if (type == DeviceType.ANDROID_TV) "com.hbo.hbonow" else "3201601007250"
                val paramountId = if (type == DeviceType.ANDROID_TV) "com.cbs.app" else "3201908019033"
                val dao = db.tvAppDao()
                val savedMax = dao.insert(TvAppEntity(deviceId = deviceId, appName = "Max / HBO", appIdentifier = maxId, launchCount = 9, isFavorite = false))
                val savedParamount = dao.insert(TvAppEntity(deviceId = deviceId, appName = "Paramount+", appIdentifier = paramountId, launchCount = 6, isFavorite = true))
                TvAppRepository(db).ensureDefaultAppsForDevice(deviceId, type)
                val max = dao.getAppById(savedMax)!!
                val paramount = dao.getAppById(savedParamount)!!
                assertEquals(if (type == DeviceType.ANDROID_TV) "com.wbd.stream" else "3202301029760", max.appIdentifier)
                assertEquals(if (type == DeviceType.ANDROID_TV) "com.cbs.ca" else "3202110025305", paramount.appIdentifier)
                assertEquals(9, max.launchCount)
                assertFalse(max.isFavorite)
                assertEquals(6, paramount.launchCount)
                assertTrue(paramount.isFavorite)
                val apps = dao.getAppsForDevice(deviceId).first()
                assertEquals(1, apps.count { it.iconKey == "max" })
                assertEquals(1, apps.count { it.iconKey == "paramount" })
            }
        } finally { db.close() }
    }

}

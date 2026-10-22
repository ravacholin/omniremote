package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TvDimmerAppsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val packageName = "com.tribalapps.tvdimmer"

    @Before
    @After
    fun resetDatabaseSingleton() {
        // Other Robolectric classes can leave this singleton open. The upgrade test
        // must reopen its version-2 file rather than reuse a previous connection.
        val instance = AppDatabase::class.java.getDeclaredField("INSTANCE")
        instance.isAccessible = true
        (instance.get(null) as? AppDatabase)?.close()
        instance.set(null, null)
    }

    @Test
    fun `new Android TVs include TvDimmer in Tools without changing Samsung defaults`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = TvAppRepository(db.tvAppDao())
            val androidId = db.tvDeviceDao().insert(device("Android", DeviceType.ANDROID_TV))
            val samsungId = db.tvDeviceDao().insert(device("Samsung", DeviceType.SAMSUNG_TIZEN))
            repo.ensureDefaultAppsForDevice(androidId, DeviceType.ANDROID_TV)
            repo.ensureDefaultAppsForDevice(androidId, DeviceType.ANDROID_TV)
            repo.ensureDefaultAppsForDevice(samsungId, DeviceType.SAMSUNG_TIZEN)

            val apps = repo.getAppsForDevice(androidId).first()
            assertEquals(1, apps.count { it.appIdentifier == packageName })
            val dimmer = apps.single { it.appIdentifier == packageName }
            assertEquals("TvDimmer - NightCompanion", dimmer.appName)
            assertEquals("Tools", dimmer.category)
            assertFalse(dimmer.isFavorite)
            assertTrue(repo.getAppsForDevice(samsungId).first().none { it.appIdentifier == packageName })
        } finally {
            db.close()
        }
    }

    @Test
    fun `upgrade adds TvDimmer once to saved Android TVs and preserves existing apps`() = runBlocking {
        val databaseName = "omni_remote_database.db"
        context.deleteDatabase(databaseName)
        val oldDb = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        val androidId = oldDb.tvDeviceDao().insert(device("Android", DeviceType.ANDROID_TV))
        val customId = oldDb.tvDeviceDao().insert(device("Custom Android", DeviceType.ANDROID_TV))
        val samsungId = oldDb.tvDeviceDao().insert(device("Samsung", DeviceType.SAMSUNG_TIZEN))
        val custom = TvAppEntity(
            deviceId = customId, appName = "My night filter", appIdentifier = packageName,
            category = "Tools", isFavorite = true, sortOrder = 2, launchCount = 7
        )
        val customAppId = oldDb.tvAppDao().insert(custom)
        val unrelated = TvAppEntity(
            deviceId = androidId, appName = "My player", appIdentifier = "my.player",
            isFavorite = true, launchCount = 12
        )
        val unrelatedId = oldDb.tvAppDao().insert(unrelated)
        oldDb.close()
        // Version 2 has the same tables; simulate upgrading an existing installation.
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, 0).use {
            it.version = 2
        }

        val db = AppDatabase.getInstance(context)
        val repo = TvAppRepository(db.tvAppDao())
        val apps = repo.getAppsForDevice(androidId).first()
        assertEquals(1, apps.count { it.appIdentifier == packageName })
        assertEquals(unrelated.copy(id = unrelatedId), apps.single { it.id == unrelatedId })
        assertEquals(listOf(custom.copy(id = customAppId)), repo.getAppsForDevice(customId).first())
        assertTrue(repo.getAppsForDevice(samsungId).first().isEmpty())

        // A later user deletion must remain deleted when defaults are checked again.
        repo.deleteApp(apps.single { it.appIdentifier == packageName })
        repo.ensureDefaultAppsForDevice(androidId, DeviceType.ANDROID_TV)
        assertTrue(repo.getAppsForDevice(androidId).first().none { it.appIdentifier == packageName })
    }

    private fun device(name: String, type: DeviceType) = TvDeviceEntity(
        name = name, deviceType = type, ipAddress = "192.168.1.${name.length}"
    )
}

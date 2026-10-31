package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabasePreservationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "omni_remote_database.db"

    @After fun resetDatabase() {
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            (get(null) as? AppDatabase)?.close()
            set(null, null)
        }
    }

    @Test fun `unsupported database version cannot erase saved TVs or tokens`() = runBlocking {
        resetDatabase()
        context.deleteDatabase(databaseName)
        val original = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        val id = original.tvDeviceDao().insert(TvDeviceEntity(name = "Authorized TV",
            deviceType = DeviceType.SAMSUNG_TIZEN, ipAddress = "192.168.1.10", authToken = "saved-token"))
        original.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, 0).use { it.version = 1 }

        val reopened = AppDatabase.getInstance(context)
        try {
            reopened.tvDeviceDao().getDeviceById(id)
            fail("An unknown schema must require a migration instead of silently deleting saved devices")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("migration", ignoreCase = true))
        } finally { resetDatabase() }

        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, 0).use { db ->
            db.rawQuery("SELECT name, authToken FROM tv_devices WHERE id = ?", arrayOf(id.toString())).use {
                assertTrue(it.moveToFirst())
                assertEquals("Authorized TV", it.getString(0))
                assertEquals("saved-token", it.getString(1))
            }
        }
    }
}

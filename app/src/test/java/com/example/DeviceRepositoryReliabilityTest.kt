package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.data.model.TvAppEntity
import com.example.data.repository.TvDeviceRepository
import com.example.data.repository.TvAppRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceRepositoryReliabilityTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: TvDeviceRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        repo = TvDeviceRepository(db.tvDeviceDao())
    }

    @After fun teardown() = db.close()

    @Test fun `adding another TV keeps a single default`() = runBlocking {
        val first = repo.saveDevice(tv("192.168.1.10"))
        repo.saveDevice(tv("192.168.1.11"))
        assertEquals(listOf(first), repo.allDevices.first().filter { it.isDefault }.map { it.id })
    }

    @Test fun `explicit new default replaces the previous default`() = runBlocking {
        repo.saveDevice(tv("192.168.1.10"))
        val second = repo.saveDevice(tv("192.168.1.11").copy(isDefault = true))
        assertEquals(listOf(second), repo.allDevices.first().filter { it.isDefault }.map { it.id })
    }

    @Test fun `rediscovery preserves the default and saved credentials`() = runBlocking {
        val id = repo.saveDevice(tv("192.168.1.10").copy(authToken = "authorized", macAddress = "AA:BB:CC:DD:EE:10"))
        val rediscovered = repo.saveDevice(tv("192.168.1.10"))
        val saved = repo.getDeviceById(id)!!
        assertEquals(id, rediscovered)
        assertTrue(saved.isDefault)
        assertEquals("authorized", saved.authToken)
        assertEquals("AA:BB:CC:DD:EE:10", saved.macAddress)
    }

    @Test fun `saving stale device metadata cannot overwrite a newly issued token`() = runBlocking {
        val id = repo.saveDevice(tv("192.168.1.10").copy(authToken = "old-token"))
        val snapshotBeforeReconnect = repo.getDeviceById(id)!!
        repo.updateAuthToken(id, "new-token")
        repo.saveDevice(snapshotBeforeReconnect.copy(macAddress = "AA:BB:CC:DD:EE:10"))
        assertEquals("new-token", repo.getDeviceById(id)!!.authToken)
        assertEquals("AA:BB:CC:DD:EE:10", repo.getDeviceById(id)!!.macAddress)
    }

    @Test fun `rediscovered hardware keeps its saved identity after a DHCP address change`() = runBlocking {
        val id = repo.saveDevice(tv("192.168.1.10").copy(macAddress = "AA:BB:CC:DD:EE:10", authToken = "authorized"))
        val appId = db.tvAppDao().insert(TvAppEntity(deviceId = id, appName = "My app", appIdentifier = "my.app"))
        val rediscoveredId = repo.saveDevice(tv("192.168.1.99").copy(macAddress = "aa-bb-cc-dd-ee-10"))
        assertEquals(id, rediscoveredId)
        assertEquals("192.168.1.99", repo.getDeviceById(id)!!.ipAddress)
        assertEquals("authorized", repo.getDeviceById(id)!!.authToken)
        assertNotNull(db.tvAppDao().getAppById(appId))
    }

    @Test fun `different hardware at a reused IP cannot replace the saved timer target or inherit credentials`() = runBlocking {
        val id = repo.saveDevice(tv("192.168.1.10").copy(macAddress = "AA:BB:CC:DD:EE:10", authToken = "authorized"))
        val replacement = tv("192.168.1.10").copy(macAddress = "AA:BB:CC:DD:EE:20")
        val replacementId = repo.saveDevice(replacement)
        assertNotEquals(id, replacementId)
        assertEquals("AA:BB:CC:DD:EE:10", repo.getDeviceById(id)!!.macAddress)
        assertEquals("authorized", repo.getDeviceById(id)!!.authToken)
        assertEquals("", repo.getDeviceById(replacementId)!!.authToken)
        assertEquals(replacementId, repo.saveDevice(replacement))
    }

    @Test fun `a different protocol at a reused IP does not convert an existing saved device`() = runBlocking {
        val id = repo.saveDevice(tv("192.168.1.10").copy(authToken = "samsung-token"))
        val androidId = repo.saveDevice(tv("192.168.1.10").copy(deviceType = DeviceType.ANDROID_TV, port = 6466))
        assertNotEquals(id, androidId)
        assertEquals(DeviceType.SAMSUNG_TIZEN, repo.getDeviceById(id)!!.deviceType)
        assertEquals("", repo.getDeviceById(androidId)!!.authToken)
    }

    @Test fun `surrounding whitespace does not create duplicate TVs`() = runBlocking {
        val id = repo.saveDevice(tv("192.168.1.10"))
        assertEquals(id, repo.saveDevice(tv(" 192.168.1.10 ")))
        assertEquals(1, repo.allDevices.first().size)
        assertEquals("192.168.1.10", repo.getDeviceById(id)!!.ipAddress)
    }

    @Test fun `selecting a missing default keeps the current default`() = runBlocking {
        val id = repo.saveDevice(tv("192.168.1.10"))
        repo.setDefaultDevice(id + 100)
        assertEquals(id, repo.getDefaultDevice()?.id)
    }

    @Test fun `deleting the default chooses the most recently connected remaining TV`() = runBlocking {
        val first = repo.saveDevice(tv("192.168.1.10"))
        repo.saveDevice(tv("192.168.1.11").copy(lastConnected = 100))
        val latest = repo.saveDevice(tv("192.168.1.12").copy(lastConnected = 200))
        repo.setDefaultDevice(first)
        repo.deleteDevice(repo.getDeviceById(first)!!)
        assertEquals(latest, repo.getDefaultDevice()?.id)
    }

    @Test fun `deleting a nondefault preserves the chosen TV`() = runBlocking {
        val first = repo.saveDevice(tv("192.168.1.10"))
        val second = repo.saveDevice(tv("192.168.1.11"))
        repo.setDefaultDevice(first)
        repo.deleteDevice(repo.getDeviceById(second)!!)
        assertEquals(first, repo.getDefaultDevice()?.id)
    }

    @Test fun `deleting the final TV leaves an empty database`() = runBlocking {
        val id = repo.saveDevice(tv("192.168.1.10"))
        repo.deleteDevice(repo.getDeviceById(id)!!)
        assertTrue(repo.allDevices.first().isEmpty())
        assertNull(repo.getDefaultDevice())
    }

    @Test fun `simultaneous rediscovery saves one TV`() = runBlocking {
        val ids = (1..8).map { async(Dispatchers.IO) { repo.saveDevice(tv("192.168.1.10")) } }.awaitAll()
        assertEquals(1, ids.toSet().size)
        assertEquals(1, repo.allDevices.first().size)
    }

    @Test fun `saving a deleted TV does not remove the remaining default`() = runBlocking {
        val first = repo.saveDevice(tv("192.168.1.10"))
        val deleted = repo.getDeviceById(first)!!
        repo.deleteDevice(deleted)
        val remaining = repo.saveDevice(tv("192.168.1.11"))
        repo.saveDevice(deleted)
        assertEquals(remaining, repo.getDefaultDevice()?.id)
        assertNull(repo.getDeviceById(first))
    }

    @Test fun `rediscovery keeps custom app shortcuts`() = runBlocking {
        val id = repo.saveDevice(tv("192.168.1.10"))
        val appId = db.tvAppDao().insert(TvAppEntity(deviceId = id, appName = "My app", appIdentifier = "my.app"))
        repo.saveDevice(tv("192.168.1.10").copy(name = "Renamed TV"))
        assertNotNull(db.tvAppDao().getAppById(appId))
    }

    @Test fun `pending app initialization skips a deleted TV`() = runBlocking {
        val id = repo.saveDevice(tv("192.168.1.10"))
        repo.deleteDevice(repo.getDeviceById(id)!!)
        TvAppRepository(db).ensureDefaultAppsForDevice(id, DeviceType.SAMSUNG_TIZEN)
        assertTrue(db.tvAppDao().getAppsForDevice(id).first().isEmpty())
    }

    private fun tv(ip: String) = TvDeviceEntity(name = "Living room", deviceType = DeviceType.SAMSUNG_TIZEN, ipAddress = ip)
}

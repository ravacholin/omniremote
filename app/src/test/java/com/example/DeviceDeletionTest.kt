package com.example

import androidx.room.withTransaction
import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.controller.UnifiedTvController
import com.example.data.local.AppDatabase
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceDeletionTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private lateinit var model: MainViewModel
    private lateinit var controller: UnifiedTvController

    @Before fun setup() {
        resetDatabase()
        application.deleteDatabase("omni_remote_database.db")
        Dispatchers.setMain(UnconfinedTestDispatcher())
        model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[MainViewModel::class.java]
        controller = MainViewModel::class.java.getDeclaredField("controller").let {
            it.isAccessible = true
            it.get(model) as UnifiedTvController
        }
    }

    @After fun teardown() {
        controller.disconnectCurrent()
        val modelJob = model.viewModelScope.coroutineContext[Job]
        store.clear()
        runBlocking { modelJob?.join() }
        resetDatabase()
        Dispatchers.resetMain()
    }

    @Test fun `deleting an active Mac cancels its target before database deletion can finish`() = runBlocking {
        assertImmediateDeletion(DeviceType.MACOS)
    }

    @Test fun `deleting an active Android TV cancels pairing before database deletion can finish`() = runBlocking {
        assertImmediateDeletion(DeviceType.ANDROID_TV)
    }

    @Test fun `deleting an active Samsung cancels its target before database deletion can finish`() = runBlocking {
        assertImmediateDeletion(DeviceType.SAMSUNG_TIZEN)
    }

    private suspend fun assertImmediateDeletion(type: DeviceType) {
        val db = AppDatabase.getInstance(application)
        val id = db.tvDeviceDao().insert(device().copy(deviceType = type, port = if (type == DeviceType.MACOS) 22 else 6466))
        val mac = db.tvDeviceDao().getDeviceById(id)!!
        model.selectDevice(mac)
        if (type == DeviceType.ANDROID_TV) model.startAndroidTvPairing()
        val session = com.example.controller.RemoteSession.getInstance(application)
        val generation = session.targetGeneration
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val transaction = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            db.withTransaction { entered.complete(Unit); release.await() }
        }
        entered.await()
        try {
            model.deleteDevice(mac)
            assertNull("The deleted target must be deselected before waiting for Room", model.activeDevice.value)
            assertTrue(session.targetGeneration > generation)
            assertEquals(ConnectionState.Disconnected, model.connectionState.value)
            assertFalse(model.isAndroidTvPairingDialogOpen.value)
        } finally { release.complete(Unit); transaction.join() }
    }

    @Test fun `deleting the final active TV disconnects the remote`() = runBlocking {
        val db = AppDatabase.getInstance(application)
        val id = db.tvDeviceDao().insert(device().copy(isDefault = true))
        val saved = db.tvDeviceDao().getDeviceById(id)!!
        // Finish startup auto-selection before deleting; otherwise its suspended
        // app initialization can reselect the stale initial database snapshot.
        withTimeout(5000) {
            while (model.activeDevice.value?.id != saved.id) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        model.deleteDevice(saved)
        withTimeout(5000) {
            while (model.activeDevice.value != null) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        assertEquals(ConnectionState.Disconnected, model.connectionState.value)
        assertFalse(model.isCurrentDeviceAndroidTv)
    }

    @Test fun `deleting the active default selects a remaining TV`() = runBlocking {
        val db = AppDatabase.getInstance(application)
        val first = db.tvDeviceDao().insert(device().copy(isDefault = true))
        val second = db.tvDeviceDao().insert(device().copy(ipAddress = "127.0.0.2"))
        val saved = db.tvDeviceDao().getDeviceById(first)!!
        // Finish startup auto-selection before deleting; otherwise its suspended
        // app initialization can reselect the stale initial database snapshot.
        withTimeout(5000) {
            while (model.activeDevice.value?.id != saved.id) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        model.deleteDevice(saved)
        withTimeout(5000) {
            while (model.activeDevice.value?.id != second) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        assertEquals(second, model.activeDevice.value?.id)
    }

    private fun device() = TvDeviceEntity(name = "TV", deviceType = DeviceType.ANDROID_TV, ipAddress = "127.0.0.1", port = 6466)

    private fun resetDatabase() {
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            (get(null) as? AppDatabase)?.close()
            set(null, null)
        }
    }
}

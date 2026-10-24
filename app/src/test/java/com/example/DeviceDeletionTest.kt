package com.example

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

    @Test fun `deleting the final active TV disconnects the remote`() = runBlocking {
        val db = AppDatabase.getInstance(application)
        val id = db.tvDeviceDao().insert(device().copy(isDefault = true))
        val saved = db.tvDeviceDao().getDeviceById(id)!!
        model.selectDevice(saved)
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
        model.selectDevice(saved)
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

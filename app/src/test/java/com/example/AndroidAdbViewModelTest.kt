package com.example

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.controller.RemoteSession
import com.example.data.local.AppDatabase
import com.example.data.model.DeviceType
import com.example.data.model.TvAppEntity
import com.example.data.model.TvDeviceEntity
import com.example.ui.MainViewModel
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidAdbViewModelTest {
    @Test fun `device card ADB configuration resolves the saved custom dimmer package`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        fun resetDatabase() {
            AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
                isAccessible = true; (get(null) as? AppDatabase)?.close(); set(null, null)
            }
        }
        resetDatabase()
        app.deleteDatabase("omni_remote_database.db")
        val db = AppDatabase.getInstance(app)
        val tv = TvDeviceEntity(id = 91, name = "FlowBox", deviceType = DeviceType.ANDROID_TV, ipAddress = "127.0.0.1")
        withContext(Dispatchers.IO) {
            db.tvDeviceDao().insert(tv)
            db.tvAppDao().insert(TvAppEntity(deviceId = 91, appName = "TvDimmer custom", appIdentifier = "com.example.customdimmer", isFavorite = true, sortOrder = -1))
        }
        val store = ViewModelStore()
        val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[MainViewModel::class.java]
        val job = model.viewModelScope.coroutineContext[Job]
        try {
            model.configureBrightnessAdb(tv)
            withTimeout(5000) {
                while (model.adbSetup.value == null) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
            }
            assertEquals("com.example.customdimmer", model.adbSetup.value?.packageName)
        } finally {
            RemoteSession.getInstance(app).clearDevice()
            store.clear()
            withTimeout(5000) {
                while (job?.isCompleted == false) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
            }
            resetDatabase()
        }
    }
}

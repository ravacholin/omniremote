package com.example

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.controller.RemoteSession
import com.example.data.local.AppDatabase
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.network.MacResult
import com.example.network.MacScripts
import com.example.ui.MainViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MacEndpointChangeTest {
    @Test fun `saving the same Mac at a new SSH port reconnects and invalidates pending commands`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val database = AppDatabase.getInstance(app)
        val store = ViewModelStore()
        val model = MainViewModel(app)
        store.put("model", model)
        val session = RemoteSession.getInstance(app)
        val hostKey = Files.createTempDirectory("mac-port-change").resolve("host.ser")
        val first = MacRemoteTest.FakeMac(hostKey)
        var second: MacRemoteTest.FakeMac? = null
        suspend fun settle(predicate: () -> Boolean) = withTimeout(5000) {
            while (!predicate()) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
        }
        try {
            val original = TvDeviceEntity(name = "Mac", deviceType = DeviceType.MACOS,
                ipAddress = "127.0.0.1", port = first.port)
            model.saveDevice(original)
            settle { model.activeDevice.value != null }
            val savedId = model.activeDevice.value!!.id
            assertTrue(session.controller.loginMac("pablo", "secret") is MacResult.Ok)
            val newEndpoint = MacRemoteTest.FakeMac(hostKey).also { it.authorizedKeys.addAll(first.authorizedKeys) }
            second = newEndpoint
            val previousGeneration = session.targetGeneration
            // DeviceManager's Add flow reuses the saved ID when IP/type match.
            model.saveDevice(original.copy(port = newEndpoint.port))
            settle { model.activeDevice.value?.port == newEndpoint.port }
            assertEquals(savedId, model.activeDevice.value!!.id)
            assertTrue("A changed SSH endpoint must invalidate pending actions", session.targetGeneration > previousGeneration)
            settle { session.controller.connectionState.value is com.example.data.model.ConnectionState.Connected }
            first.commands.clear(); newEndpoint.commands.clear()
            assertTrue(session.controller.runMacMediaScript(MacScripts.setVolume(67)) { true } is MacResult.Ok)
            assertTrue("Old endpoint must not receive the new volume", first.commands.none { it.second.startsWith("set volume output volume 67") })
            assertTrue("New endpoint must receive it", newEndpoint.commands.any { it.second.startsWith("set volume output volume 67") })
        } finally {
            session.clearDevice()
            val job = model.viewModelScope.coroutineContext[Job]!!
            store.clear()
            awaitQuickRemoteJobs(listOf(job))
            first.stop(); second?.stop()
            database.close()
            AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
        }
    }
}

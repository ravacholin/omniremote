package com.example

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.controller.RemoteSession
import com.example.controller.UnifiedTvController
import com.example.data.local.AppDatabase
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.ui.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MacLoginLifecycleTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private lateinit var model: MainViewModel
    private lateinit var controller: UnifiedTvController
    private lateinit var server: MacRemoteTest.FakeMac
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private lateinit var device: TvDeviceEntity
    private var initialJobs = emptySet<Job>()

    @Before fun setup() {
        model = MainViewModel(app)
        store.put("model", model)
        controller = RemoteSession.getInstance(app).controller
        server = MacRemoteTest.FakeMac(Files.createTempDirectory("mac-login-ui").resolve("host.ser"),
            beforeAuthorize = { entered.countDown(); release.await(5, TimeUnit.SECONDS) })
        device = TvDeviceEntity(id = 71, name = "Mac", deviceType = DeviceType.MACOS,
            ipAddress = "127.0.0.1", port = server.port)
        controller.javaClass.getDeclaredField("activeDevice").apply { isAccessible = true; set(controller, device) }
        RemoteSession.getInstance(app).selectedDevice.value = device
        model.openMacLogin()
        initialJobs = model.viewModelScope.coroutineContext[Job]!!.children.toSet()
    }

    @After fun cleanup() {
        release.countDown()
        model.closeMacLogin()
        RemoteSession.getInstance(app).clearDevice()
        val job = model.viewModelScope.coroutineContext[Job]!!
        store.clear()
        awaitQuickRemoteJobs(listOf(job))
        server.stop()
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true; (get(null) as? AppDatabase)?.close(); set(null, null)
        }
    }

    @Test fun `closing a pending login prevents credential storage and clears busy state`() {
        model.submitMacLogin("pablo", "secret")
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        model.closeMacLogin()
        assertFalse("Dismissal must immediately stop the busy indicator", model.isMacLoginInProgress.value)
        release.countDown()
        awaitLogin()
        assertFalse(model.isMacLoginDialogOpen.value)
        assertTrue("Cancelled login must not save credentials", model.tokenStore.getMacLogin(device.id) == null)
        assertFalse(controller.connectionState.value is com.example.data.model.ConnectionState.Connected)
    }

    @Test fun `repeated login submissions authorize only one key`() {
        model.submitMacLogin("pablo", "secret")
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        model.submitMacLogin("pablo", "secret")
        release.countDown()
        awaitLogin()
        assertEquals("Repeated taps must not enqueue another login", 1, server.authorizedKeys.size)
        assertFalse(model.isMacLoginInProgress.value)
    }

    private fun awaitLogin() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        // Let coroutine completions run on the paused Android looper.
        do {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        } while (model.viewModelScope.coroutineContext[Job]!!.children.any { it !in initialJobs && !it.isCompleted } &&
            System.nanoTime() < deadline)
        assertFalse("Login must settle", model.isMacLoginInProgress.value)
    }
}

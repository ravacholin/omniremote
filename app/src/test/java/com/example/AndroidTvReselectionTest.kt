package com.example

import com.example.controller.UnifiedTvController
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidTvReselectionTest {
    @Test fun `reselection preserves ongoing Android connection`() = preservesPendingSession(false, false)
    @Test fun `reselection preserves Android PIN exchange`() = preservesPendingSession(true, false)
    @Test fun `repeated reconnect preserves ongoing Android connection`() = preservesPendingSession(false, true)
    @Test fun `repeated reconnect preserves Android PIN exchange`() = preservesPendingSession(true, true)

    @Test fun `closing PIN clears waiting state and permits retry`() = preservesPendingSession(true, false, cancel = true)
    @Test fun `changing endpoint cancels pending authentication`() = preservesPendingSession(true, false, changeEndpoint = true)

    @Test fun `pairing from device picker can be cancelled then reconnected`() {
        val controller = UnifiedTvController()
        val device = TvDeviceEntity(id = 7, name = "FlowBox", deviceType = DeviceType.ANDROID_TV, ipAddress = "127.0.0.1")
        try {
            controller.setActiveDevice(device)
            controller.startAndroidTvPairing({}, { _, _ -> })
            controller.cancelAndroidTvPairing()
            assertTrue("Cancelled pairing must leave Connecting", controller.connectionState.value is ConnectionState.Disconnected)
            controller.reconnect()
            assertEquals(ConnectionState.Connecting(device.name, device.ipAddress), controller.connectionState.value)
        } finally { controller.disconnectCurrent() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `failed pairing from device picker permits a fresh connection`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val controller = UnifiedTvController()
        val device = TvDeviceEntity(id = 7, name = "FlowBox", deviceType = DeviceType.ANDROID_TV, ipAddress = "127.0.0.2")
        val result = CompletableDeferred<Boolean>()
        try {
            controller.setActiveDevice(device)
            controller.startAndroidTvPairing({}, { ok, _ -> result.complete(ok) })
            assertFalse(withTimeout(15000) { result.await() })
            assertTrue("Pairing failure must not leave a pending connection", controller.connectionState.value is ConnectionState.Error)
        } finally {
            controller.disconnectCurrent()
            Dispatchers.resetMain()
        }
    }

    private fun preservesPendingSession(pairing: Boolean, reconnect: Boolean, cancel: Boolean = false, changeEndpoint: Boolean = false) {
        val controller = UnifiedTvController()
        val device = TvDeviceEntity(id = 7, name = "FlowBox", deviceType = DeviceType.ANDROID_TV, ipAddress = "127.0.0.1")
        val initial = if (pairing) ConnectionState.WaitingAuth(device.name, "PIN")
            else ConnectionState.Connecting(device.name, device.ipAddress)
        UnifiedTvController::class.java.getDeclaredField("activeDevice").apply { isAccessible = true; set(controller, device) }
        @Suppress("UNCHECKED_CAST")
        val state = UnifiedTvController::class.java.getDeclaredField("_connectionState").let {
            it.isAccessible = true
            it.get(controller) as MutableStateFlow<ConnectionState>
        }
        state.value = initial
        val pending = Job()
        UnifiedTvController::class.java.getDeclaredField(if (pairing) "pairingJob" else "connectionJob").apply {
            isAccessible = true; set(controller, pending)
        }
        try {
            if (cancel) {
                controller.cancelAndroidTvPairing()
                assertFalse(pending.isActive)
                assertTrue(controller.connectionState.value is ConnectionState.Disconnected)
                controller.reconnect()
                assertTrue(controller.connectionState.value is ConnectionState.Connecting)
                return
            }
            if (changeEndpoint) {
                controller.setActiveDevice(device.copy(ipAddress = "127.0.0.2"))
                assertFalse(pending.isActive)
                assertEquals(ConnectionState.Connecting(device.name, "127.0.0.2"), controller.connectionState.value)
                return
            }
            if (reconnect) controller.reconnect() else controller.setActiveDevice(device.copy(name = "Living"))
            assertTrue("Same-device action must not cancel pending authentication", pending.isActive)
            assertSame(initial, controller.connectionState.value)
        } finally { controller.disconnectCurrent(); pending.cancel() }
    }
}

package com.example

import com.example.controller.UnifiedTvController
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.TvTokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SamsungReselectionTest {
    @Test fun `database token cannot override a known hardware identity conflict`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("omni_remote_tokens", Context.MODE_PRIVATE).edit().clear().commit()
        val tokens = TvTokenStore(context)
        tokens.saveSamsungToken("192.168.1.10", "old-tv-token", 7, "AA:BB:CC:DD:EE:10")
        val controller = UnifiedTvController(tokenStore = tokens)
        val old = TvDeviceEntity(id = 7, name = "Samsung", deviceType = DeviceType.SAMSUNG_TIZEN,
            ipAddress = "192.168.1.10", authToken = "old-tv-token", macAddress = "AA:BB:CC:DD:EE:10")
        val active = UnifiedTvController::class.java.getDeclaredField("activeDevice").apply { isAccessible = true }
        active.set(controller, old)
        @Suppress("UNCHECKED_CAST")
        val state = UnifiedTvController::class.java.getDeclaredField("_connectionState").let {
            it.isAccessible = true
            it.get(controller) as MutableStateFlow<ConnectionState>
        }
        state.value = ConnectionState.WaitingAuth(old.name, "Accept on TV")
        try {
            controller.setActiveDevice(old.copy(macAddress = "AA:BB:CC:DD:EE:20"))
            assertEquals("", (active.get(controller) as TvDeviceEntity).authToken)
            assertEquals("old-tv-token", tokens.getSamsungToken("192.168.1.99", macAddress = old.macAddress))
        } finally { controller.disconnectCurrent() }
    }

    @Test fun `updating the selected TV while its permission is pending preserves the session`() {
        preservesSession(ConnectionState.WaitingAuth("Samsung", "Accept on TV"))
    }

    @Test fun `reselecting the same TV during boot preserves the connection attempt`() {
        preservesSession(ConnectionState.Connecting("Samsung", "192.168.1.10"))
    }

    private fun preservesSession(initial: ConnectionState) {
        val controller = UnifiedTvController()
        val device = TvDeviceEntity(id = 7, name = "Samsung", deviceType = DeviceType.SAMSUNG_TIZEN,
            ipAddress = "192.168.1.10", authToken = "12345678")
        // Reproduce the state at the UI boundary without requiring a physical TV prompt.
        UnifiedTvController::class.java.getDeclaredField("activeDevice").apply {
            isAccessible = true
            set(controller, device)
        }
        @Suppress("UNCHECKED_CAST")
        val state = UnifiedTvController::class.java.getDeclaredField("_connectionState").let {
            it.isAccessible = true
            it.get(controller) as MutableStateFlow<ConnectionState>
        }
        state.value = initial
        val logs = controller.recentLogs.value
        try {
            controller.setActiveDevice(device.copy(macAddress = "AA:BB:CC:DD:EE:10"))
            assertSame(initial, controller.connectionState.value)
            assertEquals(logs, controller.recentLogs.value)
        } finally { controller.disconnectCurrent() }
    }
}

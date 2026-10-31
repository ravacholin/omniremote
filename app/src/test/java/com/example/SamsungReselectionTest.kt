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
import android.os.Looper
import com.example.data.model.RemoteKey
import com.example.network.SamsungTvClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.robolectric.Shadows.shadowOf

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

    @Test fun `foreground reconnects a selected TV after a normal socket close`() {
        val controller = UnifiedTvController()
        val device = TvDeviceEntity(id = 7, name = "Samsung", deviceType = DeviceType.SAMSUNG_TIZEN,
            ipAddress = "127.0.0.1", authToken = "12345678")
        UnifiedTvController::class.java.getDeclaredField("activeDevice").apply {
            isAccessible = true
            set(controller, device)
        }
        try {
            controller.reconnectIfFailed()
            assertTrue(controller.connectionState.value is ConnectionState.Connecting)
        } finally { controller.disconnectCurrent() }
    }

    @Test fun `explicit retry does not destroy Samsung permission in progress`() {
        val controller = UnifiedTvController()
        val device = TvDeviceEntity(id = 7, name = "Samsung", deviceType = DeviceType.SAMSUNG_TIZEN,
            ipAddress = "127.0.0.1")
        UnifiedTvController::class.java.getDeclaredField("activeDevice").apply {
            isAccessible = true
            set(controller, device)
        }
        @Suppress("UNCHECKED_CAST")
        val state = UnifiedTvController::class.java.getDeclaredField("_connectionState").let {
            it.isAccessible = true
            it.get(controller) as MutableStateFlow<ConnectionState>
        }
        val waiting = ConnectionState.WaitingAuth("Samsung", "Accept on TV")
        state.value = waiting
        try {
            controller.reconnect()
            assertSame(waiting, controller.connectionState.value)
        } finally { controller.disconnectCurrent() }
    }

    @Test fun `foreground preserves a key-triggered reconnect before UI state catches up`() {
        pendingKeyReconnect(foreground = true)
    }

    @Test fun `explicit retry preserves a key-triggered reconnect before UI state catches up`() {
        pendingKeyReconnect(foreground = false)
    }

    @Test fun `reselecting the TV preserves a key-triggered reconnect before UI state catches up`() {
        pendingKeyReconnect(foreground = false, reselect = true)
    }

    @Test fun `authorization diagnostics persist the actual rejection without exposing the token`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("samsung_connection_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        val token = "7654321098765432"
        val store = TvTokenStore(context)
        store.saveSamsungToken("127.0.0.1", token, 7)
        val controller = UnifiedTvController(context, store)
        val client = UnifiedTvController::class.java.getDeclaredField("samsungClient").let {
            it.isAccessible = true
            it.get(controller) as SamsungTvClient
        }
        var socket: TestSocket? = null
        SamsungTvClient::class.java.getDeclaredField("webSocketFactory").apply {
            isAccessible = true
            set(client, WebSocket.Factory { request, listener -> TestSocket(request, listener).also { socket = it } })
        }
        try {
            controller.setActiveDevice(TvDeviceEntity(id = 7, name = "Samsung", deviceType = DeviceType.SAMSUNG_TIZEN,
                ipAddress = "127.0.0.1"))
            socket!!.listener.onMessage(socket!!, """{"event":"ms.channel.unauthorized"}""")
            val saved = context.getSharedPreferences("samsung_connection_diagnostics", Context.MODE_PRIVATE)
                .getString("events", "").orEmpty()
            assertTrue("A TV rejection must survive recreating the diagnostics reader", saved.contains("authorization_rejected"))
            assertTrue(saved.contains("sha256:"))
            assertFalse("The authorization credential must never be exported", saved.contains(token))
            val report = com.example.network.SamsungConnectionDiagnostics(context).report()
            assertTrue(report.contains("authorization_rejected"))
            assertFalse(report.contains(token))
        } finally { controller.disconnectCurrent() }
    }

    private fun pendingKeyReconnect(foreground: Boolean, reselect: Boolean = false) {
        val sockets = mutableListOf<TestSocket>()
        val controller = UnifiedTvController()
        val client = UnifiedTvController::class.java.getDeclaredField("samsungClient").let {
            it.isAccessible = true
            it.get(controller) as SamsungTvClient
        }
        SamsungTvClient::class.java.getDeclaredField("webSocketFactory").apply {
            isAccessible = true
            set(client, WebSocket.Factory { request, listener -> TestSocket(request, listener).also { sockets += it } })
        }
        try {
            controller.setActiveDevice(TvDeviceEntity(id = 7, name = "Samsung", deviceType = DeviceType.SAMSUNG_TIZEN,
                ipAddress = "127.0.0.1", authToken = "12345678"))
            val first = sockets.single()
            first.listener.onMessage(first, """{"event":"ms.channel.connect"}""")
            shadowOf(Looper.getMainLooper()).idle()
            first.listener.onClosed(first, 1000, "standby")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(controller.connectionState.value is ConnectionState.Disconnected)
            assertFalse(client.sendKey(RemoteKey.HOME))
            val pending = sockets.last()
            if (reselect) {
                controller.setActiveDevice(TvDeviceEntity(id = 7, name = "Samsung", deviceType = DeviceType.SAMSUNG_TIZEN,
                    ipAddress = "127.0.0.1", macAddress = "AA:BB:CC:DD:EE:10"))
            } else if (foreground) controller.reconnectIfFailed() else controller.reconnect()
            assertFalse("Retry must not destroy a pending client session when UI still says Disconnected", pending.closed)
            assertEquals(2, sockets.size)
        } finally { controller.disconnectCurrent() }
    }

    private class TestSocket(private val req: Request, val listener: WebSocketListener) : WebSocket {
        var closed = false
        override fun request() = req
        override fun queueSize() = 0L
        override fun send(text: String) = !closed
        override fun send(bytes: ByteString) = !closed
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        override fun cancel() { closed = true }
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

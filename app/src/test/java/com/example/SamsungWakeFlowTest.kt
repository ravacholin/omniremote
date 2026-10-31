package com.example

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.controller.UnifiedTvController
import com.example.data.local.AppDatabase
import com.example.data.local.TvTokenStore
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.network.SamsungTvClient
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SamsungWakeFlowTest {
    private val device = TvDeviceEntity(id = 7, name = "Samsung", deviceType = DeviceType.SAMSUNG_TIZEN,
        ipAddress = "127.0.0.1", macAddress = "AA:BB:CC:DD:EE:10", authToken = "12345678")

    @Test fun `queued pre-wake callbacks cannot replace the current wake state`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
        for (event in listOf("failure", "unauthorized")) {
            val controller = UnifiedTvController()
            setScope(controller, this)
            val client = client(controller)
            setScope(client, this)
            val sockets = sockets(client)
            try {
                controller.setActiveDevice(device)
                val old = sockets.single()
                if (event == "failure") old.listener.onFailure(old, IOException("TV off"), null)
                else old.listener.onMessage(old, """{"event":"ms.channel.unauthorized"}""")
                // The callback is queued on Main, but the user presses WOL first.
                assertTrue(controller.prepareWakeRequest(device))
                runCurrent()
                assertTrue("Old $event must not replace the current wake state",
                    controller.connectionState.value is ConnectionState.Connecting)
                advanceTimeBy(14_999)
                runCurrent()
                controller.reconnect()
                controller.setActiveDevice(device)
                assertEquals(1, sockets.size)
                advanceTimeBy(1)
                runCurrent()
                assertEquals(2, sockets.size)
                val resumed = sockets.last()
                resumed.listener.onMessage(resumed, """{"event":"ms.channel.connect"}""")
                runCurrent()
                assertTrue(controller.connectionState.value is ConnectionState.Connected)
            } finally { controller.disconnectCurrent() }
        }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `in-app WOL polling does not create more attempts after the controlled attempt fails`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
        app.deleteDatabase("omni_remote_database.db")
        app.getSharedPreferences("omni_remote_tokens", Context.MODE_PRIVATE).edit().clear().commit()
        TvTokenStore::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        val model = MainViewModel(app)
        val controller = MainViewModel::class.java.getDeclaredField("controller").let {
            it.isAccessible = true; it.get(model) as UnifiedTvController
        }
        val client = client(controller)
        setScope(client, this)
        val sockets = sockets(client)
        @Suppress("UNCHECKED_CAST")
        val active = MainViewModel::class.java.getDeclaredField("_activeDevice").let {
            it.isAccessible = true; it.get(model) as MutableStateFlow<TvDeviceEntity?>
        }
        try {
            active.value = device
            controller.setActiveDevice(device)
            model.powerOnTv()
            // WOL itself uses the real UDP sender and bounded loopback HTTP/TCP probes.
            // Wait for those external operations; virtual time controls the reconnect.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (model.powerOnStatusMessage.value?.startsWith("Prueba Samsung") != true &&
                System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("The in-app WOL operation must complete", model.powerOnStatusMessage.value.orEmpty().startsWith("Prueba Samsung"))
            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(2, sockets.size)
            val resumed = sockets.last()
            resumed.listener.onFailure(resumed, IOException("TV did not finish starting"), null)
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("A failed controlled attempt must not start generic automatic retries", 2, sockets.size)
            assertTrue(controller.connectionState.value is ConnectionState.Error)
        } finally {
            controller.disconnectCurrent()
            model.viewModelScope.cancel()
            AppDatabase.getInstance(app).close()
            AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
            Dispatchers.resetMain()
        }
    }

    private fun client(controller: UnifiedTvController) = UnifiedTvController::class.java.getDeclaredField("samsungClient").let {
        it.isAccessible = true; it.get(controller) as SamsungTvClient
    }
    private fun setScope(target: Any, scope: TestScope) {
        target.javaClass.getDeclaredField("scope").apply { isAccessible = true; set(target, scope) }
    }
    private fun sockets(client: SamsungTvClient): MutableList<Socket> {
        val sockets = mutableListOf<Socket>()
        SamsungTvClient::class.java.getDeclaredField("webSocketFactory").apply {
            isAccessible = true
            set(client, WebSocket.Factory { request, listener -> Socket(request, listener).also { sockets += it } })
        }
        return sockets
    }
    private class Socket(private val req: Request, val listener: WebSocketListener) : WebSocket {
        override fun request() = req
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
    }
}

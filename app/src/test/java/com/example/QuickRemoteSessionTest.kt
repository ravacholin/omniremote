package com.example

import android.app.Application
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.example.controller.RemoteSession
import com.example.controller.UnifiedTvController
import com.example.data.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.example.data.local.AppDatabase
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.data.model.TvDeviceEntity
import com.example.network.SamsungTvClient
import com.example.ui.MainViewModel
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuickRemoteSessionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val stores = mutableListOf<ViewModelStore>()
    private val models = mutableListOf<MainViewModel>()
    private val controllers = mutableListOf<UnifiedTvController>()
    private val device = TvDeviceEntity(id = 7, name = "Samsung", deviceType = DeviceType.SAMSUNG_TIZEN,
        ipAddress = "127.0.0.1", authToken = "12345678", macAddress = "AA:BB:CC:DD:EE:10")

    @After fun cleanup() {
        controllers.forEach { it.disconnectCurrent() }
        val jobs = models.mapNotNull { it.viewModelScope.coroutineContext[kotlinx.coroutines.Job] }
        stores.forEach { it.clear() }
        awaitQuickRemoteJobs(jobs)
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true; (get(null) as? AppDatabase)?.close(); set(null, null)
        }
    }

    @Test fun `recreating the screen preserves the selected device and authorized socket`() {
        val first = model()
        val controller = controller(first)
        val sockets = installSockets(controller)
        first.selectDevice(device)
        sockets.single().connect()
        shadowOf(Looper.getMainLooper()).idle()
        val second = model()
        assertSame("The notification and recreated screen must reuse the same controller", controller, controller(second))
        assertEquals(device.id, second.activeDevice.value?.id)
        assertEquals(1, sockets.size)
        first.sendKey(RemoteKey.VOL_UP)
        await { sockets.single().commands().isNotEmpty() }
        assertEquals(listOf("KEY_VOLUP"), sockets.single().commands())
    }

    @Test fun `play pause state is shared between screen owners`() {
        val first = model()
        val controller = controller(first)
        val sockets = installSockets(controller)
        first.selectDevice(device)
        sockets.single().connect()
        shadowOf(Looper.getMainLooper()).idle()
        first.togglePlayPause()
        await { sockets.single().commands().size == 1 }
        val second = model()
        assertFalse("Reopening a screen must not reset the estimated playback state", second.isPlaying.value)
        second.togglePlayPause()
        await { sockets.single().commands().size == 2 }
        assertEquals(listOf("KEY_PAUSE", "KEY_PLAY"), sockets.single().commands())
    }

    @Test fun `a queued key for the previous TV never reaches the newly selected TV`() {
        val controller = UnifiedTvController().also { controllers += it }
        val sockets = installSockets(controller)
        val client = samsungClient(controller)
        controller.setActiveDevice(device)
        sockets.single().connect()
        shadowOf(Looper.getMainLooper()).idle()
        synchronized(client) {
            controller.sendKey(RemoteKey.POWER)
            controller.setActiveDevice(device.copy(id = 8, ipAddress = "127.0.0.2"))
            sockets.last().connect()
        }
        await { controller.recentLogs.value.any { it.command.contains("POWER") } }
        assertTrue("A POWER queued for TV 7 must never turn off TV 8", sockets.last().commands().isEmpty())
    }

    @Test fun `foreground preserves Android TV authorization already in progress`() {
        val session = RemoteSession.getInstance(app)
        val controller = session.controller.also { controllers += it }
        val tv = device.copy(deviceType = DeviceType.ANDROID_TV, port = 6466)
        session.selectedDevice.value = tv
        UnifiedTvController::class.java.getDeclaredField("activeDevice").apply { isAccessible = true; set(controller, tv) }
        @Suppress("UNCHECKED_CAST")
        val state = UnifiedTvController::class.java.getDeclaredField("_connectionState").let {
            it.isAccessible = true; it.get(controller) as MutableStateFlow<ConnectionState>
        }
        val waiting = ConnectionState.WaitingAuth(tv.name, "Introduce el PIN")
        state.value = waiting
        session.resumeConnection()
        assertSame("Rotating/reopening must not cancel an existing PIN exchange", waiting, state.value)
    }

    @Test fun `saving a new device changes the control target on the main thread`() {
        val model = model()
        installSockets(controller(model))
        model.selectDevice(device)
        val emittedOn = java.util.concurrent.atomic.AtomicReference<Thread>()
        val observer = CoroutineScope(Dispatchers.Unconfined).launch {
            model.activeDevice.filter { it != null && it.id != 7L }.first()
            emittedOn.set(Thread.currentThread())
        }
        try {
            model.saveDevice(device.copy(id = 0, name = "Saved TV", ipAddress = "127.0.0.2"))
            await { emittedOn.get() != null }
            assertSame("Selection must not interleave with notification validation on Main",
                Looper.getMainLooper().thread, emittedOn.get())
        } finally { observer.cancel() }
    }

    private fun model(): MainViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[MainViewModel::class.java].also { models += it }
    }
    private fun controller(model: MainViewModel): UnifiedTvController = MainViewModel::class.java
        .getDeclaredField("controller").let { it.isAccessible = true; (it.get(model) as UnifiedTvController).also { c -> controllers += c } }
    private fun await(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3000
        while (!done() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        assertTrue("Timed out waiting for command dispatch", done())
    }
}

internal fun samsungClient(controller: UnifiedTvController): SamsungTvClient = UnifiedTvController::class.java
    .getDeclaredField("samsungClient").let { it.isAccessible = true; it.get(controller) as SamsungTvClient }
internal fun installSockets(controller: UnifiedTvController): MutableList<RemoteSocket> {
    val sockets = mutableListOf<RemoteSocket>()
    SamsungTvClient::class.java.getDeclaredField("webSocketFactory").apply {
        isAccessible = true
        set(samsungClient(controller), WebSocket.Factory { request, listener ->
            RemoteSocket(request, listener).also { sockets += it }
        })
    }
    return sockets
}
internal class RemoteSocket(private val req: Request, val listener: WebSocketListener) : WebSocket {
    private val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var closed = false
    fun connect() = listener.onMessage(this, """{"event":"ms.channel.connect"}""")
    fun commands(): List<String> = messages.mapNotNull {
        JSONObject(it).optJSONObject("params")?.optString("DataOfCmd")?.takeIf { key -> key.isNotBlank() }
    }
    override fun request() = req
    override fun queueSize() = 0L
    override fun send(text: String): Boolean { if (closed) return false; messages += text; return true }
    override fun send(bytes: ByteString) = !closed
    override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
    override fun cancel() { closed = true }
}

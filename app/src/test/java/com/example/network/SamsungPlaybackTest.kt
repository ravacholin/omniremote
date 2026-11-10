package com.example.network

import com.example.controller.UnifiedTvController
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.data.model.TvDeviceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SamsungPlaybackTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `controller sends Samsung text as one Unicode input command`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val controller = UnifiedTvController()
        fun field(name: String) = controller.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val scope = field("scope").get(controller) as CoroutineScope
        val client = field("samsungClient").get(controller) as SamsungTvClient
        val socket = RecordingSocket()
        try {
            field("activeDevice").set(controller, TvDeviceEntity(id = 1, name = "Samsung", deviceType = DeviceType.SAMSUNG_TIZEN, ipAddress = "127.0.0.1"))
            attach(client, socket)
            val before = scope.coroutineContext[Job]!!.children.toSet()
            controller.sendText("Hola café 😀 %s & ' TV")
            runBlocking {
                withTimeout(3000) {
                    scope.coroutineContext[Job]!!.children.filter { it !in before }.toList().forEach { it.join() }
                }
            }
            assertEquals(1, socket.messages.size)
            val payload = JSONObject(socket.messages.single())
            assertEquals("ms.remote.control", payload.getString("method"))
            val params = payload.getJSONObject("params")
            assertEquals("SendInputString", params.getString("TypeOfRemote"))
            assertEquals("base64", params.getString("DataOfCmd"))
            assertEquals("Hola café 😀 %s & ' TV", String(java.util.Base64.getDecoder().decode(params.getString("Cmd")), Charsets.UTF_8))
        } finally {
            controller.disconnectCurrent()
            scope.coroutineContext[Job]!!.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun `pause sends only the pause command`() = assertSingleCommand(RemoteKey.PAUSE, "KEY_PAUSE")
    @Test fun `play sends only the play command`() = assertSingleCommand(RemoteKey.PLAY, "KEY_PLAY")
    @Test fun `play pause toggles only once`() = assertSingleCommand(RemoteKey.PLAY_PAUSE, "KEY_PLAYPAUSE")

    @Test fun `failed playback transmission is reported`() {
        val client = SamsungTvClient()
        val socket = RecordingSocket(accept = false)
        attach(client, socket)
        assertFalse(client.sendKey(RemoteKey.PAUSE))
        assertEquals(1, socket.messages.size)
    }

    @Test fun `text is not queued before Samsung authorization or for empty input`() {
        val client = SamsungTvClient()
        val socket = RecordingSocket()
        SamsungTvClient::class.java.getDeclaredField("webSocket").apply {
            isAccessible = true
            set(client, socket)
        }
        assertFalse(client.sendText("hello"))
        attach(client, socket)
        assertFalse(client.sendText(""))
        assertTrue(socket.messages.isEmpty())
    }

    @Test fun `failed Samsung text transmission is reported`() {
        val client = SamsungTvClient()
        val socket = RecordingSocket(accept = false)
        attach(client, socket)
        assertFalse(client.sendText("hello"))
        assertEquals(1, socket.messages.size)
    }

    private fun assertSingleCommand(key: RemoteKey, expected: String) {
        val client = SamsungTvClient()
        val socket = RecordingSocket()
        attach(client, socket)
        assertTrue(client.sendKey(key))
        val commands = socket.messages.map { JSONObject(it).getJSONObject("params").getString("DataOfCmd") }
        assertEquals(listOf(expected), commands)
    }

    private fun attach(client: SamsungTvClient, socket: WebSocket) {
        SamsungTvClient::class.java.getDeclaredField("isConnected").apply {
            isAccessible = true
            setBoolean(client, true)
        }
        SamsungTvClient::class.java.getDeclaredField("webSocket").apply {
            isAccessible = true
            set(client, socket)
        }
    }

    private class RecordingSocket(private val accept: Boolean = true) : WebSocket {
        val messages = mutableListOf<String>()
        override fun request() = Request.Builder().url("http://127.0.0.1").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages.add(text); return accept }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
    }
}

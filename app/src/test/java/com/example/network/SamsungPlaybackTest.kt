package com.example.network

import com.example.data.model.RemoteKey
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

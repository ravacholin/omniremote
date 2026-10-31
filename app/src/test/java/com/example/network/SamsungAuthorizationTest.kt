package com.example.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.TvTokenStore
import com.example.data.model.RemoteKey
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SamsungAuthorizationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: TvTokenStore
    private val factory = TvSockets()
    private val ip = "192.168.1.10"

    @Before fun setup() {
        context.getSharedPreferences("omni_remote_tokens", Context.MODE_PRIVATE).edit().clear().commit()
        store = TvTokenStore(context)
    }

    @Test fun `keys wait for TV authorization instead of being queued during boot`() {
        val client = client()
        client.connect(ip)
        val socket = factory.sockets.single()
        assertFalse(client.sendKey(RemoteKey.POWER))
        assertTrue(socket.messages.isEmpty())
        socket.event("ms.channel.connect", "12345678")
        assertTrue(client.sendKey(RemoteKey.VOL_UP))
        assertTrue(socket.messages.last().contains("KEY_VOLUP"))
    }

    @Test fun `failed key sends cannot replace a pending authorization socket`() {
        val client = client()
        client.connect(ip)
        val socket = factory.sockets.single().apply { acceptsWrites = false }
        repeat(5) { assertFalse(client.sendKey(RemoteKey.HOME)) }
        assertEquals(1, factory.sockets.size)
        assertFalse(socket.closed)
        socket.event("ms.channel.connect", "12345678")
        assertEquals("12345678", TvTokenStore(context).getSamsungToken(ip))
    }

    @Test fun `a late token from an old socket cannot overwrite the current authorization`() {
        val client = client()
        client.connect(ip)
        val old = factory.sockets.single()
        client.disconnect()
        client.connect(ip)
        factory.sockets.last().event("ms.channel.connect", "22222222")
        old.event("ms.channel.connect", "11111111")
        assertEquals("22222222", client.token)
        assertEquals("22222222", TvTokenStore(context).getSamsungToken(ip))
    }

    @Test fun `recreated client reuses authorization after standby and app restart`() {
        val first = client()
        first.connect(ip)
        factory.sockets.last().event("ms.channel.connect", "12345678")
        first.disconnect()
        val restored = SamsungTvClient(TvTokenStore(context), webSocketFactory = factory)
        restored.connect(ip)
        assertEquals("12345678", factory.sockets.last().request().url.queryParameter("token"))
        factory.sockets.last().event("ms.channel.connect")
        assertTrue(restored.isConnected)
        assertTrue(restored.sendKey(RemoteKey.HOME))
    }

    @Test fun `explicitly empty credential does not fall back to an IP token`() {
        store.saveSamsungToken(ip, "belongs-to-another-tv", 1, "AA:BB:CC:DD:EE:10")
        val client = client()
        client.connect(ip, token = "")
        assertNull(factory.sockets.single().request().url.queryParameter("token"))
        client.disconnect()
    }

    @Test fun `transport failure releases authorization wait so keys can reconnect with saved token`() {
        store.saveSamsungToken(ip, "12345678")
        val client = client()
        client.connect(ip)
        val socket = factory.sockets.single()
        socket.event("ms.channel.unauthorized")
        socket.acceptsWrites = false
        socket.listener.onFailure(socket, IOException("TV entered standby"), null)
        assertFalse(client.sendKey(RemoteKey.HOME))
        assertEquals(2, factory.sockets.size)
        assertEquals("12345678", factory.sockets.last().request().url.queryParameter("token"))
    }

    @Test fun `token on an unauthorized event does not authenticate the session`() {
        val client = client()
        client.connect(ip)
        factory.sockets.last().event("ms.channel.unauthorized", "invalid")
        assertFalse(client.isConnected)
        assertEquals("", store.getSamsungToken(ip))
    }

    @Test fun `automatic reconnect uses a token renewed by another session`() {
        val client = client()
        client.connect(ip)
        val socket = factory.sockets.single()
        socket.event("ms.channel.connect", "11111111")
        // A separate session, such as Sleep, received an updated token from the TV.
        store.saveSamsungToken(ip, "22222222")
        socket.acceptsWrites = false
        socket.listener.onFailure(socket, IOException("TV entered standby"), null)
        assertFalse(client.sendKey(RemoteKey.HOME))
        assertEquals("22222222", factory.sockets.last().request().url.queryParameter("token"))
    }

    private fun client() = SamsungTvClient(store, webSocketFactory = factory)

    // Only the external TV transport is replaced; session handling and disk writes are real.
    private class TvSockets : WebSocket.Factory {
        val sockets = mutableListOf<TvSocket>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            TvSocket(request, listener).also { sockets += it }
    }

    private class TvSocket(private val request: Request, val listener: WebSocketListener) : WebSocket {
        val messages = mutableListOf<String>()
        var acceptsWrites = true
        var closed = false
        fun event(event: String, token: String? = null) {
            val data = if (token == null) "{}" else "{\"token\":\"$token\"}"
            listener.onMessage(this, "{\"event\":\"$event\",\"data\":$data}")
        }
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages += text; return acceptsWrites }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        override fun cancel() { closed = true }
    }
}

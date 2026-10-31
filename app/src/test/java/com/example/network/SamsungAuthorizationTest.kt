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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.concurrent.thread
import java.util.concurrent.TimeUnit
import okhttp3.Protocol

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

    @Test fun `terminal socket events cannot restore an obsolete authorization`() {
        for (terminal in listOf("failure", "closing", "closed", "timeout")) {
            val client = client()
            client.connect(ip)
            val old = factory.sockets.last()
            old.event("ms.channel.connect", "11111111")
            when (terminal) {
                "failure" -> old.listener.onFailure(old, IOException("standby"), null)
                "closing" -> old.listener.onClosing(old, 1000, "standby")
                "closed" -> old.listener.onClosed(old, 1000, "standby")
                else -> old.event("ms.channel.timeOut")
            }
            store.saveSamsungToken(ip, "22222222")
            old.event("ms.channel.connect", "11111111")
            assertFalse("$terminal must retire the session", client.isConnected)
            assertEquals(terminal, "22222222", TvTokenStore(context).getSamsungToken(ip))
            client.disconnect()
        }
    }

    @Test fun `a connection broadcast identifying another remote cannot replace our token`() {
        store.saveSamsungToken(ip, "12345678")
        val client = client()
        client.connect(ip)
        val socket = factory.sockets.last()
        socket.listener.onMessage(socket, """{"event":"ms.channel.connect","data":{
            "id":"other-client","token":"99999999","clients":[
                {"id":"other-client","deviceName":"T3RoZXJSZW1vdGU=","attributes":{"name":"T3RoZXJSZW1vdGU="}}
            ]}}""")
        assertFalse(client.isConnected)
        assertEquals("12345678", store.getSamsungToken(ip))
        socket.listener.onMessage(socket, """{"event":"ms.channel.connect","data":{
            "id":"our-client","token":"22222222","clients":[
                {"id":"other-client","deviceName":"T3RoZXJSZW1vdGU="},
                {"id":"our-client","deviceName":"T21uaVJlbW90ZQ==","attributes":{"name":"T21uaVJlbW90ZQ=="}}
            ]}}""")
        assertTrue(client.isConnected)
        assertEquals("22222222", store.getSamsungToken(ip))
        client.disconnect()
    }

    @Test fun `repeated connect preserves a socket awaiting the TV permission`() {
        val client = client()
        client.connect(ip)
        val socket = factory.sockets.last()
        client.connect(ip)
        assertFalse("Retry must keep the socket receiving the approved token", socket.closed)
        assertEquals(1, factory.sockets.size)
        socket.event("ms.channel.unauthorized")
        client.connect(ip)
        assertFalse(socket.closed)
        assertEquals(1, factory.sockets.size)
        socket.event("ms.channel.connect", "12345678")
        assertTrue(client.isConnected)
        assertEquals("12345678", store.getSamsungToken(ip))
        client.disconnect()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `stalled transport eventually fails without losing its saved token`() = runTest {
        store.saveSamsungToken(ip, "12345678")
        val errors = mutableListOf<String>()
        val client = SamsungTvClient(store, onConnectionChanged = { _, error -> error?.let(errors::add) },
            webSocketFactory = factory)
        SamsungTvClient::class.java.getDeclaredField("scope").apply { isAccessible = true; set(client, this@runTest) }
        client.connect(ip)
        val socket = factory.sockets.last()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals("A silent socket must finish instead of blocking every future retry", 1, errors.size)
        assertTrue(socket.closed)
        socket.event("ms.channel.connect", "99999999")
        assertFalse(client.isConnected)
        assertEquals("12345678", TvTokenStore(context).getSamsungToken(ip))
        client.connect(ip)
        assertEquals(2, factory.sockets.size)
        assertEquals("12345678", factory.sockets.last().request().url.queryParameter("token"))
        client.disconnect()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `open transport without channel authorization has a finite wait`() = runTest {
        val errors = mutableListOf<String>()
        val client = SamsungTvClient(store, onConnectionChanged = { _, error -> error?.let(errors::add) },
            webSocketFactory = factory)
        SamsungTvClient::class.java.getDeclaredField("scope").apply { isAccessible = true; set(client, this@runTest) }
        client.connect(ip)
        val socket = factory.sockets.last()
        socket.listener.onOpen(socket, Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols").build())
        socket.event("ms.channel.unauthorized")
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, errors.size)
        assertTrue(socket.closed)
        assertFalse(client.isConnected)
        assertEquals("A timeout must not silently create a tokenless authorization request", 1, factory.sockets.size)
        client.disconnect()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `authorization cancels the deadline for the live session`() = runTest {
        val errors = mutableListOf<String>()
        val client = SamsungTvClient(store, onConnectionChanged = { _, error -> error?.let(errors::add) },
            webSocketFactory = factory)
        SamsungTvClient::class.java.getDeclaredField("scope").apply { isAccessible = true; set(client, this@runTest) }
        client.connect(ip)
        factory.sockets.last().event("ms.channel.connect", "12345678")
        advanceTimeBy(120_000)
        runCurrent()
        assertTrue(errors.isEmpty())
        assertTrue(client.isConnected)
        assertTrue(client.sendKey(RemoteKey.HOME))
        client.disconnect()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `an already resumed transport deadline cannot close the authorization phase`() = runTest {
        val client = client()
        SamsungTvClient::class.java.getDeclaredField("scope").apply { isAccessible = true; set(client, this@runTest) }
        client.connect(ip)
        val socket = factory.sockets.last()
        runCurrent()
        val timerThread: Thread
        synchronized(client) {
            timerThread = thread(name = "expired-samsung-transport") { advanceTimeBy(12_001) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (timerThread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
            assertEquals("Timer must have resumed and reached the held monitor", Thread.State.BLOCKED, timerThread.state)
            socket.listener.onOpen(socket, Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1)
                .code(101).message("Switching Protocols").build())
        }
        timerThread.join(3000)
        assertFalse(timerThread.isAlive)
        assertFalse("The transport deadline was canceled by the WebSocket upgrade", socket.closed)
        socket.event("ms.channel.connect", "12345678")
        assertTrue(client.isConnected)
        client.disconnect()
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

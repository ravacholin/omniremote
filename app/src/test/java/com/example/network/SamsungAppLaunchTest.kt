package com.example.network

import com.example.data.model.StreamingAppCatalog
import com.example.data.model.TvAppShortcut
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Real HTTP launch requests; only the TV's authorized remote socket is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SamsungAppLaunchTest {
    @Test fun `menu launches each streaming app over HTTP with its TV-specific ID`() = runBlocking {
        withTv { client, socket, server ->
            socket.event("""{"event":"ed.installedApp.get","data":{"data":[
                {"appId":"regional-flow","name":"Cablevisión Flow","app_type":2},
                {"appId":"regional-max","name":"HBO Max","app_type":2},
                {"appId":"regional-paramount","name":"Paramount+","app_type":2},
                {"appId":"regional-disney","name":"Disney+","app_type":2},
                {"appId":"regional-apple","name":"Apple TV","app_type":1}
            ]}}""")
            val expectedIds = listOf("regional-flow", "regional-max", "regional-paramount", "regional-disney", "regional-apple")
            StreamingAppCatalog.apps.zip(expectedIds).forEach { (app, expectedId) ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
                assertTrue(app.name, client.launchAppById(IP, app.samsungId).isSuccess)
                assertHttpLaunch(server, expectedId)
            }
            assertTrue("HTTP success must not launch the app twice", socket.launches().isEmpty())
        }
    }

    @Test fun `shortcuts use HTTP even when the remote socket accepts every write`() = runBlocking {
        withTv { client, socket, server ->
            for (shortcut in TvAppShortcut.entries) {
                server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
                assertTrue(shortcut.title, client.launchApp(IP, shortcut).isSuccess)
                assertHttpLaunch(server, shortcut.samsungAppId)
            }
            assertTrue(socket.launches().isEmpty())
        }
    }

    @Test fun `TVs without HTTP launching retain type-specific websocket fallback`() = runBlocking {
        withTv { client, socket, server ->
            socket.event("""{"event":"ed.installedApp.get","data":{"data":[
                {"appId":"regional-max","name":"HBO Max","app_type":2},
                {"appId":"regional-apple","name":"Apple TV","app_type":1}
            ]}}""")
            for ((id, expectedId, action) in listOf(
                Triple("3202301029760", "regional-max", "DEEP_LINK"),
                Triple("3201807016597", "regional-apple", "NATIVE_LAUNCH")
            )) {
                server.enqueue(MockResponse().setResponseCode(404))
                assertTrue(client.launchAppById(IP, id).isSuccess)
                assertHttpLaunch(server, expectedId)
                val params = socket.launches().last().getJSONObject("params")
                assertEquals("host", params.getString("to"))
                val data = params.getJSONObject("data")
                assertEquals(expectedId, data.getString("appId"))
                assertEquals(action, data.getString("action_type"))
            }
        }
    }

    @Test fun `HTTP rejection and failed socket write report failure`() = runBlocking {
        withTv { client, socket, server ->
            socket.acceptsWrites = false
            server.enqueue(MockResponse().setResponseCode(403))
            val result = client.launchApp(IP, TvAppShortcut.NETFLIX)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("403"))
            assertHttpLaunch(server, "3201907018807")
            assertEquals(1, socket.launches().size)
        }
    }

    @Test fun `HTTP transport failure still permits websocket launching`() = runBlocking {
        withTv { client, socket, server ->
            repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
            assertTrue(client.launchApp(IP, TvAppShortcut.NETFLIX).isSuccess)
            assertHttpLaunch(server, "3201907018807")
            val data = socket.launches().single().getJSONObject("params").getJSONObject("data")
            assertEquals("3201907018807", data.getString("appId"))
            assertEquals("DEEP_LINK", data.getString("action_type"))
        }
    }

    @Test fun `HTTP response after reconnect cannot launch on the replacement session`() = runBlocking {
        withTv { client, socket, server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    client.connect(IP)
                    socket.event("""{"event":"ms.channel.connect","data":{}}""")
                    return MockResponse().setResponseCode(404)
                }
            }
            assertTrue(client.launchApp(IP, TvAppShortcut.NETFLIX).isFailure)
            assertHttpLaunch(server, "3201907018807")
            assertTrue("An obsolete launch must not target the new socket", socket.launches().isEmpty())
            assertTrue("The replacement session remains usable", client.isConnected)
        }
    }

    private fun assertHttpLaunch(server: MockWebServer, expectedId: String) {
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull("The app must reach Samsung's HTTP launch endpoint", request)
        assertEquals("POST", request!!.method)
        assertEquals("/api/v2/applications/$expectedId", request.path)
    }

    private suspend fun withTv(block: suspend (SamsungTvClient, TvSocket, MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName(IP), 8001)
            val socket = TvSocket()
            val client = SamsungTvClient(webSocketFactory = socket)
            try {
                client.connect(IP)
                socket.event("""{"event":"ms.channel.connect","data":{}}""")
                block(client, socket, server)
            } finally { client.disconnect() }
        }
    }

    private class TvSocket : WebSocket, WebSocket.Factory {
        private val messages = mutableListOf<String>()
        private lateinit var listener: WebSocketListener
        var acceptsWrites = true
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.listener = listener
            return this
        }
        fun event(message: String) = listener.onMessage(this, message)
        fun launches() = messages.map(::JSONObject).filter { it.optJSONObject("params")?.optString("event") == "ed.apps.launch" }
        override fun request() = Request.Builder().url("http://$IP").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages.add(text); return acceptsWrites }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
    }

    private companion object { const val IP = "127.0.0.1" }
}

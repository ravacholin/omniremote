package com.example.network

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.controller.UnifiedTvController
import com.example.data.local.TvTokenStore
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.data.model.TvDeviceEntity
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real OkHttp TLS and WebSocket framing; only the external TV is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SamsungSessionIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ip = "127.0.0.1"
    private val mac = "AA:BB:CC:DD:EE:10"
    private val device = TvDeviceEntity(id = 7, name = "Samsung", deviceType = DeviceType.SAMSUNG_TIZEN,
        ipAddress = ip, macAddress = mac)

    @Before fun reset() {
        context.getSharedPreferences("omni_remote_tokens", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `five TLS reconnects renew and restore the token with only one initial permission`() {
        TvServer().use { tv ->
            repeat(5) { cycle ->
                val ready = CountDownLatch(1)
                val disconnected = CountDownLatch(1)
                val client = SamsungTvClient(TvTokenStore(context), onConnectionChanged = { connected, _ ->
                    if (connected) ready.countDown() else disconnected.countDown()
                })
                try {
                    client.connect(ip)
                    assertTrue("Authorization cycle $cycle", ready.await(5, TimeUnit.SECONDS))
                    assertEquals(tv.token, TvTokenStore(context).getSamsungToken(ip))
                    assertTrue(client.sendKey(RemoteKey.POWER))
                    assertEquals("KEY_POWER", tv.keys.poll(5, TimeUnit.SECONDS))
                    tv.sockets.poll(5, TimeUnit.SECONDS)!!.close(1000, "standby")
                    assertTrue(disconnected.await(5, TimeUnit.SECONDS))
                    assertFalse(client.isConnected)
                } finally { client.disconnect() }
            }
            assertEquals("A valid saved token must not ask permission again", 1, tv.prompts.get())
        }
    }

    @Test fun `controller restores a normally closed Samsung on foreground using the persisted token`() {
        TvServer().use { tv ->
            val controller = UnifiedTvController(context, TvTokenStore(context))
            try {
                controller.setActiveDevice(device)
                awaitCondition { controller.connectionState.value is ConnectionState.Connected }
                controller.sendKey(RemoteKey.POWER)
                assertEquals("KEY_POWER", tv.keys.poll(5, TimeUnit.SECONDS))
                tv.sockets.poll(5, TimeUnit.SECONDS)!!.close(1000, "standby")
                awaitCondition { controller.connectionState.value is ConnectionState.Disconnected }
                controller.reconnectIfFailed()
                awaitCondition { controller.connectionState.value is ConnectionState.Connected }
                controller.sendKey(RemoteKey.VOL_UP)
                assertEquals("KEY_VOLUP", tv.keys.poll(5, TimeUnit.SECONDS))
                assertEquals(1, tv.prompts.get())
            } finally { controller.disconnectCurrent() }
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Samsung flow did not reach the expected state")
    }

    private inner class TvServer : AutoCloseable {
        private val server = MockWebServer()
        val prompts = AtomicInteger()
        val sockets = LinkedBlockingQueue<WebSocket>()
        val keys = LinkedBlockingQueue<String>()
        @Volatile var token: String? = null
            private set
        private val serial = AtomicInteger(10000000)

        init {
            server.useHttps(AndroidTvCertificateManager.getOrCreateSslContext(context).socketFactory, false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("/api/v2/channels/samsung.remote.control", request.requestUrl!!.encodedPath)
                    assertEquals("T21uaVJlbW90ZQ==", request.requestUrl!!.queryParameter("name"))
                    val supplied = request.requestUrl!!.queryParameter("token")
                    if (token == null || supplied != token) prompts.incrementAndGet()
                    val issued = serial.incrementAndGet().toString()
                    token = issued
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            sockets.offer(webSocket)
                            webSocket.send("""{"event":"ms.channel.connect","data":{"id":"omni-$issued",
                                "token":"$issued","clients":[{"id":"omni-$issued","deviceName":"T21uaVJlbW90ZQ==",
                                "attributes":{"name":"T21uaVJlbW90ZQ=="},"isHost":false}]}}""")
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val message = JSONObject(text)
                            if (message.optString("method") == "ms.remote.control") {
                                keys.offer(message.getJSONObject("params").getString("DataOfCmd"))
                            }
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }
                    })
                }
            }
            server.start(InetAddress.getByName(ip), 8002)
        }

        override fun close() { server.close() }
    }
}

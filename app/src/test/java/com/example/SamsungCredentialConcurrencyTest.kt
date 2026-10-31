package com.example

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.example.controller.UnifiedTvController
import com.example.data.local.TvTokenStore
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.network.SamsungTvClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SamsungCredentialConcurrencyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ip = "127.0.0.7"
    private val mac = "AA:BB:CC:DD:EE:10"
    private val device = TvDeviceEntity(id = 7, name = "Samsung", deviceType = DeviceType.SAMSUNG_TIZEN,
        ipAddress = ip, authToken = "old-token")

    @Before fun reset() {
        context.getSharedPreferences("omni_remote_tokens", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `separate store instances serialize renewal while an older commit is blocked`() {
        val real = context.getSharedPreferences("omni_remote_tokens", Context.MODE_PRIVATE)
        val beforeCommit = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val delayed = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val editor = real.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean {
                        if (Thread.currentThread().name == "token-first-writer") {
                            beforeCommit.countDown()
                            check(releaseCommit.await(5, TimeUnit.SECONDS))
                        }
                        return editor.commit()
                    }
                }
            }
        }
        val wrapped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = delayed
        }
        val firstStore = TvTokenStore(wrapped)
        val secondStore = TvTokenStore(wrapped)
        val first = Thread({
            try { firstStore.saveSamsungToken(ip, "old-token", 7, mac) } catch (e: Throwable) { failures.add(e) }
        }, "token-first-writer")
        val second = Thread({
            secondStarted.countDown()
            try { secondStore.saveSamsungToken(ip, "new-token", 7) } catch (e: Throwable) { failures.add(e) }
        }, "token-second-writer")
        first.start()
        try {
            assertTrue(beforeCommit.await(5, TimeUnit.SECONDS))
            second.start()
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (second.isAlive && second.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        } finally {
            releaseCommit.countDown()
            first.join(5000)
            second.join(5000)
        }
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals("new-token", firstStore.getSamsungToken(ip, 7, mac))
    }

    @Test fun `delayed controller callback cannot persist an older token again`() {
        val tokens = TvTokenStore(context)
        val controller = UnifiedTvController(tokenStore = tokens)
        val active = UnifiedTvController::class.java.getDeclaredField("activeDevice").apply { isAccessible = true }
        active.set(controller, device)
        val client = UnifiedTvController::class.java.getDeclaredField("samsungClient").let {
            it.isAccessible = true
            it.get(controller) as SamsungTvClient
        }
        @Suppress("UNCHECKED_CAST")
        val callback = SamsungTvClient::class.java.getDeclaredField("onTokenReceived").let {
            it.isAccessible = true
            it.get(client) as (String) -> Unit
        }
        try {
            tokens.saveSamsungToken(ip, "old-token", device.id)
            tokens.saveSamsungToken(ip, "sleep-renewed-token", device.id)
            callback("old-token")
            assertEquals("sleep-renewed-token", tokens.getSamsungToken(ip, device.id))
        } finally { controller.disconnectCurrent() }
    }

    @Test fun `delayed MAC discovery binds the latest token from a separate session`() {
        val tokens = TvTokenStore(context)
        tokens.saveSamsungToken(ip, "old-token", device.id)
        val controller = UnifiedTvController(tokenStore = tokens)
        UnifiedTvController::class.java.getDeclaredField("activeDevice").apply {
            isAccessible = true
            set(controller, device)
        }
        val client = UnifiedTvController::class.java.getDeclaredField("samsungClient").let {
            it.isAccessible = true
            it.get(controller) as SamsungTvClient
        }
        SamsungTvClient::class.java.getDeclaredField("currentToken").apply { isAccessible = true; set(client, "old-token") }
        val requestReceived = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val server = ServerSocket(8001, 1, InetAddress.getByName(ip)).apply { soTimeout = 5000 }
        val executor = Executors.newSingleThreadExecutor()
        val response = executor.submit {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                check(reader.readLine().startsWith("GET /api/v2/"))
                while (!reader.readLine().isNullOrEmpty()) { }
                requestReceived.countDown()
                check(releaseResponse.await(5, TimeUnit.SECONDS))
                val body = "{\"device\":{\"wifiMac\":\"$mac\"}}"
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n" + body).toByteArray())
            }
        }
        try {
            UnifiedTvController::class.java.getDeclaredMethod("resolveDeviceMac", TvDeviceEntity::class.java).apply {
                isAccessible = true
                invoke(controller, device)
            }
            assertTrue(requestReceived.await(5, TimeUnit.SECONDS))
            tokens.saveSamsungToken(ip, "sleep-renewed-token", device.id)
            releaseResponse.countDown()
            response.get(5, TimeUnit.SECONDS)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (tokens.getSamsungToken("192.168.1.99", macAddress = mac).isBlank() && System.nanoTime() < deadline) Thread.yield()
            assertEquals("sleep-renewed-token", tokens.getSamsungToken("192.168.1.99", macAddress = mac))
            assertEquals("sleep-renewed-token", tokens.getSamsungToken(ip, device.id, mac))
        } finally {
            releaseResponse.countDown()
            controller.disconnectCurrent()
            server.close()
            executor.shutdownNow()
        }
    }
}

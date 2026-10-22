package com.example.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.TvTokenStore
import com.example.data.model.RemoteKey
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidTvRapidInputTest {
    private fun bytes(field: Int, value: ByteArray) = ByteArrayOutputStream().apply {
        AndroidTvProtocol.writeVarint(this, (field shl 3) or 2)
        AndroidTvProtocol.writeVarint(this, value.size)
        write(value)
    }.toByteArray()

    @Test fun `rapid presses during recovery share one reconnect and all reach TV`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ssl = AndroidTvCertificateManager.getOrCreateSslContext(context)
        val server = ssl.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket
        server.needClientAuth = true
        val executor = Executors.newCachedThreadPool()
        val sessions = AtomicInteger()
        val dropped = CountDownLatch(1)
        val dropInitial = CountDownLatch(1)
        val reconnectConfigured = CountDownLatch(1)
        val allowRestart = CountDownLatch(1)
        val count = 20
        val healthyPresses = List(200) { if (it % 2 == 0) RemoteKey.DOWN else RemoteKey.RIGHT }
        val received = CountDownLatch(count + healthyPresses.size)
        val keys = Collections.synchronizedList(mutableListOf<Int>())
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val client = AndroidTvClient(context, TvTokenStore(context), onConnectionChanged = { connected, _ ->
            if (!connected) dropped.countDown()
        }, remotePort = server.localPort, pairingPort = 1)
        val acceptor = executor.submit {
            try {
                server.accept().close() // connect() reachability probe
                while (!server.isClosed) {
                    val socket = server.accept() as SSLSocket
                    val session = sessions.incrementAndGet()
                    executor.submit {
                        try {
                            socket.use {
                                socket.soTimeout = 10000
                                val input = socket.inputStream
                                val output = socket.outputStream
                                AndroidTvProtocol.writePacket(output, bytes(1, byteArrayOf(8, 103)))
                                check(AndroidTvProtocol.hasField(AndroidTvProtocol.readPacket(input), 1))
                                AndroidTvProtocol.writePacket(output, bytes(2, byteArrayOf()))
                                check(AndroidTvProtocol.hasField(AndroidTvProtocol.readPacket(input), 2))
                                if (session == 2) {
                                    reconnectConfigured.countDown()
                                    check(allowRestart.await(10, TimeUnit.SECONDS))
                                }
                                AndroidTvProtocol.writePacket(output, bytes(40, byteArrayOf(8, 1)))
                                if (session == 1) {
                                    check(dropInitial.await(10, TimeUnit.SECONDS))
                                } else {
                                    // Keepalive replies must remain valid between rapid key frames.
                                    AndroidTvProtocol.writePacket(output, bytes(8, byteArrayOf(8, 55)))
                                    while (true) {
                                        val packet = AndroidTvProtocol.readPacket(input)
                                        AndroidTvProtocol.getFieldBytes(packet, 10)?.let { key ->
                                            keys.add(AndroidTvProtocol.getFieldVarint(key, 1)!!)
                                            received.countDown()
                                        } ?: check(packet.contentEquals(bytes(9, byteArrayOf(8, 55))))
                                    }
                                }
                            }
                        } catch (_: java.io.EOFException) {
                            // Client closes replaced sessions and the final session during cleanup.
                        } catch (_: SocketException) {
                        } catch (error: Throwable) {
                            errors.add(error)
                        }
                    }
                }
            } catch (_: SocketException) {
            }
        }
        try {
            assertTrue(client.connect("127.0.0.1"))
            dropInitial.countDown()
            assertTrue(dropped.await(5, TimeUnit.SECONDS))
            val first = async(Dispatchers.IO) { client.sendKey(RemoteKey.UP) }
            assertTrue(reconnectConfigured.await(5, TimeUnit.SECONDS))
            // Start every remaining call while the first handshake owns the connect mutex.
            val rest = List(count - 1) {
                async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { client.sendKey(RemoteKey.UP) }
            }
            allowRestart.countDown()
            assertTrue((listOf(first) + rest).awaitAll().all { it })
            // Send a tight, unthrottled burst on the healthy connection as well.
            for (key in healthyPresses) assertTrue(client.sendKey(key))
            assertTrue(received.await(5, TimeUnit.SECONDS))
            assertEquals(emptyList<Throwable>(), errors.toList())
            assertEquals(List(count) { RemoteKey.UP.androidKeyCode } + healthyPresses.map { it.androidKeyCode }, keys.toList())
            assertEquals("A burst must reuse the restored session", 2, sessions.get())
            assertTrue(client.isConnected)
        } finally {
            dropInitial.countDown()
            allowRestart.countDown()
            client.disconnect()
            server.close()
            acceptor.get(5, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }
}

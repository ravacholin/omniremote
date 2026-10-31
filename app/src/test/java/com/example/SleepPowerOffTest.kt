package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.network.AndroidTvCertificateManager
import com.example.network.AndroidTvProtocol
import com.example.network.AndroidTvClient
import com.example.sleep.TvSleepPowerOff
import com.example.sleep.SleepTimerManager
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.io.EOFException
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SleepPowerOffTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `sleep uses the remote control port even when discovery saved another service port`() = runBlocking {
        for (savedPort in listOf(8009, 8008, 6467, 5555, 6466)) {
            checkSavedPortPowerOff(savedPort)
        }
    }

    private suspend fun checkSavedPortPowerOff(savedPort: Int) {
        val server = (AndroidTvCertificateManager.getOrCreateSslContext(context).serverSocketFactory
            .createServerSocket(6466, 10, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).apply {
            needClientAuth = true
            soTimeout = 10000
        }
        val executor = Executors.newSingleThreadExecutor()
        val packet = executor.submit<ByteArray> {
            server.accept().close() // TCP reachability probe.
            (server.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 10000
                val input = socket.inputStream
                val output = socket.outputStream
                AndroidTvProtocol.writePacket(output, bytes(1, byteArrayOf(8, 0xE3.toByte(), 4)))
                assertTrue(AndroidTvProtocol.hasField(AndroidTvProtocol.readPacket(input), 1))
                AndroidTvProtocol.writePacket(output, bytes(2, byteArrayOf(8, 0xE3.toByte(), 4)))
                assertTrue(AndroidTvProtocol.hasField(AndroidTvProtocol.readPacket(input), 2))
                AndroidTvProtocol.writePacket(output, bytes(40, byteArrayOf(8, 1)))
                val key = AndroidTvProtocol.readPacket(input)
                AndroidTvProtocol.writePacket(output, bytes(40, byteArrayOf())) // started defaults to false.
                key
            }
        }
        try {
            val device = TvDeviceEntity(id = 7, name = "Saved TV", deviceType = DeviceType.ANDROID_TV,
                ipAddress = "127.0.0.1", port = savedPort)
            assertTrue("Sleep must use Remote v2 even with saved discovery port $savedPort",
                TvSleepPowerOff(context).powerOff(device))
            val key = AndroidTvProtocol.getFieldBytes(packet.get(10, TimeUnit.SECONDS), 10)!!
            assertEquals(26, AndroidTvProtocol.getFieldVarint(key, 1))
            assertEquals(3, AndroidTvProtocol.getFieldVarint(key, 2))
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun `an already sleeping HDMI device is never power toggled`() = runBlocking {
        checkPowerResult(initiallyOn = false, response = null, expectedSuccess = true, expectedKey = null)
    }

    @Test fun `a rejected power command is a failure rather than a successful socket write`() = runBlocking {
        val rejectedKey = bytes(10, byteArrayOf(8, 26, 16, 3))
        checkPowerResult(initiallyOn = true, response = bytes(3, byteArrayOf(8, 1) + bytes(2, rejectedKey)),
            expectedSuccess = false, expectedKey = 26)
    }

    @Test fun `a device that never confirms standby does not report success`() = runBlocking {
        checkPowerResult(initiallyOn = true, response = null, expectedSuccess = false, expectedKey = 26)
    }

    @Test fun `losing the socket is not evidence that the device entered standby`() = runBlocking {
        checkPowerResult(initiallyOn = true, response = null, expectedSuccess = false, expectedKey = 26,
            disconnectAfterKey = true)
    }

    @Test fun `missing power status capability prevents a blind toggle`() = runBlocking {
        checkPowerResult(initiallyOn = true, response = null, expectedSuccess = false, expectedKey = null,
            features = 3)
    }

    @Test fun `cancellation while waiting to write prevents a power toggle`() = runBlocking {
        val server = (AndroidTvCertificateManager.getOrCreateSslContext(context).serverSocketFactory
            .createServerSocket(0, 10, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).apply {
            needClientAuth = true
            soTimeout = 10000
        }
        val executor = Executors.newSingleThreadExecutor()
        val received = executor.submit<ByteArray?> {
            server.accept().close()
            (server.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 10000
                val input = socket.inputStream
                val output = socket.outputStream
                AndroidTvProtocol.writePacket(output, bytes(1, byteArrayOf(8, 0xE3.toByte(), 4)))
                AndroidTvProtocol.readPacket(input)
                AndroidTvProtocol.writePacket(output, bytes(2, byteArrayOf(8, 0xE3.toByte(), 4)))
                AndroidTvProtocol.readPacket(input)
                AndroidTvProtocol.writePacket(output, bytes(40, byteArrayOf(8, 1)))
                try { AndroidTvProtocol.readPacket(input) } catch (_: EOFException) { null }
            }
        }
        val client = AndroidTvClient(context, remotePort = server.localPort)
        try {
            assertTrue(client.connect("127.0.0.1", server.localPort))
            // Hold the actual dispatch monitor to reproduce cancellation during a blocked write.
            val lock = AndroidTvClient::class.java.getDeclaredField("writeLock").apply { isAccessible = true }.get(client)!!
            val checked = CountDownLatch(1)
            val command = synchronized(lock) {
                async(Dispatchers.Default) { client.powerOff { checked.countDown(); true } }.also {
                    assertTrue(checked.await(10, TimeUnit.SECONDS))
                    it.cancel()
                }
            }
            try { command.await(); fail("Expected cancellation") } catch (_: kotlinx.coroutines.CancellationException) { }
            client.disconnect()
            assertNull("A cancelled dispatch must not emit POWER", received.get(10, TimeUnit.SECONDS))
        } finally {
            client.disconnect()
            server.close()
            executor.shutdownNow()
        }
    }

    private suspend fun checkPowerResult(initiallyOn: Boolean, response: ByteArray?, expectedSuccess: Boolean,
        expectedKey: Int?, features: Int = 611, disconnectAfterKey: Boolean = false) {
        val server = (AndroidTvCertificateManager.getOrCreateSslContext(context).serverSocketFactory
            .createServerSocket(6466, 10, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).apply {
            needClientAuth = true
            soTimeout = 10000
        }
        val executor = Executors.newSingleThreadExecutor()
        val received = executor.submit<ByteArray?> {
            server.accept().close()
            (server.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 10000
                val input = socket.inputStream
                val output = socket.outputStream
                val featureValue = ByteArrayOutputStream().apply {
                    AndroidTvProtocol.writeVarint(this, 8)
                    AndroidTvProtocol.writeVarint(this, features)
                }.toByteArray()
                AndroidTvProtocol.writePacket(output, bytes(1, featureValue))
                AndroidTvProtocol.readPacket(input)
                AndroidTvProtocol.writePacket(output, bytes(2, featureValue))
                AndroidTvProtocol.readPacket(input)
                AndroidTvProtocol.writePacket(output, bytes(40, if (initiallyOn) byteArrayOf(8, 1) else byteArrayOf()))
                val key = try { AndroidTvProtocol.readPacket(input) } catch (_: EOFException) { null }
                if (disconnectAfterKey) return@use key
                if (key != null) {
                    if (response != null) AndroidTvProtocol.writePacket(output, response)
                    // Keep the session open until the client finishes; silence is not an acknowledgement.
                    try { AndroidTvProtocol.readPacket(input); fail("Power must not be retried") } catch (_: EOFException) { }
                }
                key
            }
        }
        try {
            val success = TvSleepPowerOff(context).powerOff(TvDeviceEntity(id = 7, name = "HDMI device",
                deviceType = DeviceType.ANDROID_TV, ipAddress = "127.0.0.1", port = server.localPort))
            val key = received.get(10, TimeUnit.SECONDS)?.let { AndroidTvProtocol.getFieldBytes(it, 10)!! }
            assertEquals(expectedSuccess, success)
            if (expectedKey == null) assertNull("An already sleeping device must receive no key", key)
            else assertEquals(expectedKey, key?.let { AndroidTvProtocol.getFieldVarint(it, 1) })
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun `Mac is refused without running a power toggle`() = runBlocking {
        assertFalse(TvSleepPowerOff(context).powerOff(TvDeviceEntity(id = 8, name = "Mac",
            deviceType = DeviceType.MACOS, ipAddress = "127.0.0.1")))
    }

    @Test fun `an unreachable TV reports failure`() = runBlocking {
        assertFalse(TvSleepPowerOff(context).powerOff(TvDeviceEntity(id = 7, name = "Offline",
            deviceType = DeviceType.ANDROID_TV, ipAddress = "127.0.0.2", port = 6466)))
    }

    @Test fun `Samsung without saved authorization never opens its remote channel`() = runBlocking {
        val server = ServerSocket(8002, 1, InetAddress.getByName("127.0.0.9")).apply { soTimeout = 800 }
        val executor = Executors.newSingleThreadExecutor()
        val connection = executor.submit<Boolean> {
            try { server.accept().use { true } } catch (_: SocketTimeoutException) { false }
        }
        try {
            assertFalse(TvSleepPowerOff(context).powerOff(TvDeviceEntity(id = 654, name = "Unpaired",
                deviceType = DeviceType.SAMSUNG_TIZEN, ipAddress = "127.0.0.9")))
            assertFalse("Sleep must not trigger a Samsung authorization prompt", connection.get(3, TimeUnit.SECONDS))
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun `cancelling reprogramming or deleting during connection prevents the old sleep key`() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        for (action in listOf("cancel", "reprogram", "delete")) {
            val server = (AndroidTvCertificateManager.getOrCreateSslContext(context).serverSocketFactory
                .createServerSocket(6466, 10, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).apply {
                needClientAuth = true
                soTimeout = 10000
            }
            val executor = Executors.newSingleThreadExecutor()
            val connected = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val packet = executor.submit<ByteArray?> {
                server.accept().close()
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10000
                    val input = socket.inputStream
                    val output = socket.outputStream
                    AndroidTvProtocol.writePacket(output, bytes(1, byteArrayOf(8, 0xE3.toByte(), 4)))
                    AndroidTvProtocol.readPacket(input)
                    AndroidTvProtocol.writePacket(output, bytes(2, byteArrayOf(8, 0xE3.toByte(), 4)))
                    AndroidTvProtocol.readPacket(input)
                    connected.countDown()
                    check(resume.await(10, TimeUnit.SECONDS))
                    AndroidTvProtocol.writePacket(output, bytes(40, byteArrayOf(8, 1)))
                    try { AndroidTvProtocol.readPacket(input) } catch (_: EOFException) { null }
                }
            }
            val manager = SleepTimerManager(context)
            manager.cancel()
            val device = TvDeviceEntity(id = 7, name = "Saved TV", deviceType = DeviceType.ANDROID_TV,
                ipAddress = "127.0.0.1", port = server.localPort)
            val timer = manager.advance(device)!!
            ShadowSystemClock.advanceBy(Duration.ofMinutes(30))
            assertNotNull(manager.consumeDue(timer.token))
            try {
                val result = async(Dispatchers.Default) {
                    TvSleepPowerOff(context).powerOff(device) { manager.isExecutionCurrent(timer.token) }
                }
                assertTrue(connected.await(10, TimeUnit.SECONDS))
                when (action) {
                    "cancel" -> manager.cancel()
                    "reprogram" -> manager.advance(device.copy(id = 8))
                    "delete" -> manager.cancelForDevice(device.id)
                }
                resume.countDown()
                assertFalse(action, result.await())
                assertNull("No stale key after $action", packet.get(10, TimeUnit.SECONDS))
            } finally {
                resume.countDown()
                manager.cancel()
                server.close()
                executor.shutdownNow()
            }
        }
    }

    private fun bytes(field: Int, value: ByteArray) = ByteArrayOutputStream().apply {
        AndroidTvProtocol.writeVarint(this, (field shl 3) or 2)
        AndroidTvProtocol.writeVarint(this, value.size)
        write(value)
    }.toByteArray()
}

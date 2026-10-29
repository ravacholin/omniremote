package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.network.AndroidTvCertificateManager
import com.example.network.AndroidTvProtocol
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

    @Test fun `a fresh authenticated session sends sleep 223 to the saved TV`() = runBlocking {
        val server = (AndroidTvCertificateManager.getOrCreateSslContext(context).serverSocketFactory
            .createServerSocket(0, 10, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).apply {
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
                AndroidTvProtocol.readPacket(input)
            }
        }
        try {
            val device = TvDeviceEntity(id = 7, name = "Saved TV", deviceType = DeviceType.ANDROID_TV,
                ipAddress = "127.0.0.1", port = server.localPort)
            assertTrue(TvSleepPowerOff(context).powerOff(device))
            val key = AndroidTvProtocol.getFieldBytes(packet.get(10, TimeUnit.SECONDS), 10)!!
            assertEquals(223, AndroidTvProtocol.getFieldVarint(key, 1))
            assertEquals(3, AndroidTvProtocol.getFieldVarint(key, 2))
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
                .createServerSocket(0, 10, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).apply {
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

package com.example

import com.example.controller.UnifiedTvController
import com.example.data.model.DeviceType
import com.example.data.model.TvAppEntity
import com.example.data.model.TvDeviceEntity
import com.example.network.AndroidTvClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CommandTargetReliabilityTest {
    private fun field(instance: Any, name: String) = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun readyClient(client: AndroidTvClient, output: ByteArrayOutputStream) {
        field(client, "activeIp").set(client, "127.0.0.1")
        field(client, "isPaired").set(client, true)
        field(client, "isConnected").set(client, true)
        field(client, "negotiatedFeatures").set(client, 615)
        field(client, "remoteSocket").set(client, SSLContext.getDefault().socketFactory.createSocket())
        field(client, "remoteOutput").set(client, output)
    }

    @Test fun `text waiting for the write lock cannot cross to a replacement TV session`() =
        assertWaitingCommandIsDiscarded("sendText") { it.sendText("private search") }

    @Test fun `app link waiting for the write lock is discarded when the TV session changes`() =
        assertWaitingCommandIsDiscarded("launchAppLink") { it.launchAppLink("https://example.com/watch") }

    private fun assertWaitingCommandIsDiscarded(
        method: String,
        client: AndroidTvClient = AndroidTvClient(),
        invalidate: ((AndroidTvClient, ByteArrayOutputStream) -> Unit)? = null,
        command: suspend (AndroidTvClient) -> Boolean
    ) {
        val previous = ByteArrayOutputStream()
        val replacement = ByteArrayOutputStream()
        readyClient(client, previous)
        val oldSocket = field(client, "remoteSocket").get(client) as java.net.Socket
        val lock = field(client, "writeLock").get(client)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val started = java.util.concurrent.CountDownLatch(1)
        try {
            val pending: java.util.concurrent.Future<Boolean>
            synchronized(lock) {
                pending = executor.submit<Boolean> {
                    started.countDown()
                    runBlocking { command(client) }
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                // Both transports use Dispatchers.IO; detect their blocked writer by its stack.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (Thread.getAllStackTraces().none { (thread, stack) ->
                        thread.state == Thread.State.BLOCKED && stack.any { it.className.startsWith("com.example.network.AndroidTvClient\$$method") }
                    }) {
                    check(System.nanoTime() < deadline) { "Command did not reach the write lock" }
                    Thread.sleep(1)
                }
                if (invalidate == null) readyClient(client, replacement) else invalidate(client, replacement)
            }
            assertFalse(pending.get(3, TimeUnit.SECONDS))
            assertEquals(0, previous.size())
            assertEquals("The new TV must not receive the previous TV's command", 0, replacement.size())
        } finally { oldSocket.close(); client.disconnect(); executor.shutdownNow() }
    }

    @Test fun `controller invalidates queued text even when the transport socket has not changed`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val controller = UnifiedTvController()
        val client = field(controller, "androidClient").get(controller) as AndroidTvClient
        try {
            field(controller, "activeDevice").set(controller, TvDeviceEntity(id = 1, name = "TV", deviceType = DeviceType.ANDROID_TV, ipAddress = "127.0.0.1"))
            assertWaitingCommandIsDiscarded("sendText", client, invalidate = { _, _ ->
                (field(controller, "commandGeneration").get(controller) as java.util.concurrent.atomic.AtomicLong).incrementAndGet()
            }) {
                controller.sendText("private search")
                withTimeout(3000) { controller.recentLogs.first { it.isNotEmpty() } }.first().success
            }
        } finally {
            controller.disconnectCurrent()
            (field(controller, "scope").get(controller) as CoroutineScope).cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun `apps from the previous device grid cannot launch on the selected TV`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val controller = UnifiedTvController()
        val client = field(controller, "androidClient").get(controller) as AndroidTvClient
        val bytes = ByteArrayOutputStream()
        try {
            field(controller, "activeDevice").set(controller, TvDeviceEntity(id = 2, name = "New TV", deviceType = DeviceType.ANDROID_TV, ipAddress = "127.0.0.1"))
            readyClient(client, bytes)
            val scope = field(controller, "scope").get(controller) as CoroutineScope
            val existingJobs = scope.coroutineContext[Job]!!.children.toSet()
            controller.launchTvApp(TvAppEntity(deviceId = 1, appName = "Old TV app", appIdentifier = "my.old.app"))
            runBlocking { withTimeout(3000) { scope.coroutineContext[Job]!!.children.filter { it !in existingJobs }.toList().joinAll() } }
            assertEquals("A stale app belongs to a different TV", 0, bytes.size())
        } finally {
            controller.disconnectCurrent()
            (field(controller, "scope").get(controller) as CoroutineScope).cancel()
            Dispatchers.resetMain()
        }
    }
}

package com.example

import com.example.controller.UnifiedTvController
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.network.MacResult
import com.example.network.MacSshClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MacVolumeDestinationTest {
    @Test fun `queued volume never reaches a newly selected Mac`() = fixture { controller, client, server, dispatcher ->
        select(controller, mac(1, server.port))
        controller.setMacVolume(81)
        controller.disconnectCurrent()
        select(controller, mac(2, server.port))
        assertTrue(client.login(2, "127.0.0.1", server.port, "pablo", "secret") is MacResult.Ok)
        server.commands.clear()
        dispatcher.scheduler.runCurrent()
        assertTrue("An old slider request must not reach the new target", server.commands.isEmpty())
    }

    @Test fun `reconnecting the same Mac discards old queued volume`() = fixture { controller, client, server, dispatcher ->
        val device = mac(1, server.port)
        select(controller, device)
        controller.setMacVolume(81)
        controller.disconnectCurrent()
        select(controller, device)
        assertTrue(client.login(1, "127.0.0.1", server.port, "pablo", "secret") is MacResult.Ok)
        server.commands.clear()
        dispatcher.scheduler.runCurrent()
        assertTrue("A previous connection's slider request must not be replayed", server.commands.isEmpty())
    }

    @Test fun `slider keeps the latest pending level on the same Mac`() = fixture { controller, client, server, dispatcher ->
        select(controller, mac(1, server.port))
        assertTrue(client.login(1, "127.0.0.1", server.port, "pablo", "secret") is MacResult.Ok)
        server.commands.clear()
        controller.setMacVolume(12)
        controller.setMacVolume(28)
        controller.setMacVolume(73)
        dispatcher.scheduler.runCurrent()
        assertEquals(1, server.commands.size)
        assertTrue(server.commands.single().second.startsWith("set volume output volume 73\n"))
    }

    @Test fun `volume and device changes preserve content queue order across database restart`() = fixture { controller, client, server, dispatcher ->
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "mac-queue-volume-regression.db"
        context.deleteDatabase(name)
        fun openDb() = androidx.room.Room.databaseBuilder(context, com.example.data.local.AppDatabase::class.java, name).build()
        var db = openDb()
        try {
            kotlinx.coroutines.runBlocking {
                val firstMac = mac(1, server.port)
                val secondMac = mac(2, server.port)
                db.tvDeviceDao().insert(firstMac)
                db.tvDeviceDao().insert(secondMac)
                val queue = com.example.share.MacLinkQueue(db.macLinkDao())
                queue.enqueue(1, com.example.share.SharedMacLink.parse("https://example.com/first")!!)
                queue.enqueue(1, com.example.share.SharedMacLink.parse("https://example.com/second")!!)
                queue.enqueue(2, com.example.share.SharedMacLink.parse("https://example.com/other-mac")!!)
                select(controller, firstMac)
                assertTrue(client.login(1, "127.0.0.1", server.port, "pablo", "secret") is MacResult.Ok)
                controller.setMacVolume(35)
                dispatcher.scheduler.runCurrent()
                controller.setMacVolume(81)
                controller.disconnectCurrent()
                select(controller, secondMac)
                assertTrue(client.login(2, "127.0.0.1", server.port, "pablo", "secret") is MacResult.Ok)
                dispatcher.scheduler.runCurrent()
                assertTrue(queue.openNext(1, controller::openMacLink) is MacResult.Failed)
                controller.disconnectCurrent()
                db.close()
                db = openDb()
                val restartedQueue = com.example.share.MacLinkQueue(db.macLinkDao())
                val opened = mutableListOf<String>()
                repeat(2) {
                    assertTrue(restartedQueue.openNext(1) { id, url ->
                        assertEquals(1L, id); opened += url; MacResult.Ok("")
                    } is MacResult.Ok)
                }
                assertEquals(listOf("https://example.com/first", "https://example.com/second"), opened)
                assertEquals("https://example.com/other-mac", db.macLinkDao().first(2)!!.url)
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun mac(id: Long, port: Int) = TvDeviceEntity(id = id, name = "Mac $id",
        deviceType = DeviceType.MACOS, ipAddress = "127.0.0.1", port = port)

    private fun select(controller: UnifiedTvController, device: TvDeviceEntity) {
        controller.javaClass.getDeclaredField("activeDevice").apply { isAccessible = true; set(controller, device) }
    }

    private fun fixture(test: (UnifiedTvController, MacSshClient, MacRemoteTest.FakeMac, kotlinx.coroutines.test.TestDispatcher) -> Unit) {
        val dispatcher = StandardTestDispatcher()
        val controller = UnifiedTvController(ioDispatcher = dispatcher)
        val client = controller.javaClass.getDeclaredField("macClient").let { it.isAccessible = true; it.get(controller) as MacSshClient }
        val server = MacRemoteTest.FakeMac(Files.createTempDirectory("volume-target").resolve("host.ser"))
        try { test(controller, client, server, dispatcher) }
        finally {
            controller.disconnectCurrent()
            (controller.javaClass.getDeclaredField("scope").let { it.isAccessible = true; it.get(controller) } as CoroutineScope).cancel()
            dispatcher.scheduler.runCurrent()
            server.stop()
        }
    }
}

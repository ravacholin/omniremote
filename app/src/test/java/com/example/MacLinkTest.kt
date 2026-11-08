package com.example

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.network.MacResult
import com.example.share.MacLinkQueue
import com.example.share.SharedMacLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MacLinkTest {
    @Test fun `extracts shared URL with title and converts geo addresses`() {
        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Mirá este vídeo\nhttps://youtu.be/abc?t=42")
            .putExtra(Intent.EXTRA_SUBJECT, "Mi vídeo")
        val link = SharedMacLink.fromIntent(share)!!
        assertEquals("https://youtu.be/abc?t=42", link.url)
        assertEquals("Mi vídeo", link.title)
        assertEquals("https://maps.apple.com/?ll=40.4,-3.7&q=Caf%C3%A9", SharedMacLink.parse("geo:40.4,-3.7?q=Caf%C3%A9")!!.url)
        assertNull(SharedMacLink.fromIntent(Intent(Intent.ACTION_VIEW).setData(android.net.Uri.parse("https://example.com"))))
    }

    @Test fun `prose punctuation is excluded while balanced URL parentheses are preserved`() {
        assertEquals("https://youtu.be/abc", SharedMacLink.parse("Mirá (https://youtu.be/abc).")!!.url)
        assertEquals("https://example.com/watch", SharedMacLink.parse("Mirá [vídeo](https://example.com/watch)")!!.url)
        assertEquals("https://example.com/wiki/Foo_(bar)", SharedMacLink.parse("Mirá (https://example.com/wiki/Foo_(bar)).")!!.url)
        assertEquals("https://example.com/watch", SharedMacLink.parse("https://example.com/watch\nVídeo")!!.url)
        assertEquals("https://maps.apple.com/?ll=40.4,-3.7&q=Caf%C3%A9", SharedMacLink.parse("geo:0,0?q=40.4,-3.7(Caf%C3%A9)")!!.url)
        assertEquals("https://maps.apple.com/?q=Caf%C3%A9", SharedMacLink.parse("geo:0,0?q=Caf%C3%A9")!!.url)
    }

    @Test fun `rejects executable schemes credentials malformed hosts and control characters`() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "ftp://host/file", "https://", "https://user:pass@example.com", "https://example.com/\u0000evil", "hola").forEach {
            assertNull(it, SharedMacLink.parse(it))
        }
        assertEquals("https://example.com/a?x=1&y=2", SharedMacLink.parse("https://example.com/a?x=1&y=2")!!.url)
    }

    @Test fun `queue persists order per Mac and retains failed opening`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("mac-links-test.db")
        fun db() = Room.databaseBuilder(context, AppDatabase::class.java, "mac-links-test.db").build()
        var database = db()
        val mac = TvDeviceEntity(id = 7, name = "Mac", deviceType = DeviceType.MACOS, ipAddress = "127.0.0.1")
        database.tvDeviceDao().insert(mac)
        database.tvDeviceDao().insert(mac.copy(id = 8))
        var queue = MacLinkQueue(database.macLinkDao())
        queue.enqueue(7, SharedMacLink.parse("https://example.com/1")!!)
        queue.enqueue(7, SharedMacLink.parse("https://example.com/2")!!)
        queue.enqueue(8, SharedMacLink.parse("https://example.com/other")!!)
        database.close()
        database = db()
        queue = MacLinkQueue(database.macLinkDao())
        try {
            assertEquals(listOf("https://example.com/1", "https://example.com/2"), database.macLinkDao().observe(7).first().map { it.url })
            assertTrue(queue.openNext(7) { _, _ -> MacResult.Failed("offline") } is MacResult.Failed)
            assertEquals(2, database.macLinkDao().observe(7).first().size)
            val sent = mutableListOf<String>()
            assertTrue(queue.openNext(7) { id, url -> assertEquals(7L, id); sent += url; MacResult.Ok("") } is MacResult.Ok)
            assertEquals(listOf("https://example.com/1"), sent)
            assertEquals("https://example.com/2", database.macLinkDao().first(7)!!.url)
            database.tvDeviceDao().delete(mac)
            assertTrue(database.macLinkDao().observe(7).first().isEmpty())
            assertEquals(1, database.macLinkDao().observe(8).first().size)
        } finally { database.close() }
    }

    @Test fun `version three migration preserves authorized devices and their shortcuts`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "omni_remote_database.db"
        val field = AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        (field.get(null) as? AppDatabase)?.close()
        field.set(null, null)
        context.deleteDatabase(name)
        val original = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        original.tvDeviceDao().insert(TvDeviceEntity(id = 71, name = "Mi Mac", deviceType = DeviceType.MACOS,
            ipAddress = "192.168.1.71", authToken = "preserved-token"))
        original.tvAppDao().insert(com.example.data.model.TvAppEntity(id = 17, deviceId = 71,
            appName = "Favorito", appIdentifier = "app.test", isFavorite = true, launchCount = 9))
        original.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, 0).use {
            it.execSQL("DROP TABLE mac_links")
            it.version = 3
        }
        val upgraded = AppDatabase.getInstance(context)
        try {
            val device = upgraded.tvDeviceDao().getDeviceById(71)!!
            assertEquals("Mi Mac", device.name)
            assertEquals("preserved-token", device.authToken)
            val app = upgraded.tvAppDao().getAppById(17)!!
            assertTrue(app.isFavorite)
            assertEquals(9, app.launchCount)
            MacLinkQueue(upgraded.macLinkDao()).enqueue(71, SharedMacLink.parse("https://example.com/new")!!)
            assertEquals("https://example.com/new", upgraded.macLinkDao().first(71)!!.url)
        } finally { upgraded.close(); field.set(null, null) }
    }

    @Test fun `open keeps hostile URL characters in data and rejects executable schemes`() {
        val url = "https://example.com/watch?q=%22';%24(test)&other=ñ"
        val script = com.example.network.MacScripts.openLink(url)
        val payload = Regex("initWithBase64EncodedStringOptions\\('([^']+)', 0\\)").find(script.source)!!.groupValues[1]
        assertEquals(url, String(android.util.Base64.decode(payload, android.util.Base64.DEFAULT), Charsets.UTF_8))
        assertFalse(script.source.contains(url))
        assertEquals("/usr/bin/osascript -l JavaScript -", script.command)
        assertThrows(IllegalArgumentException::class.java) { com.example.network.MacScripts.openLink("file:///etc/passwd") }
    }

    @Test fun `open a new share leaves older queued links in their original order`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.tvDeviceDao().insert(TvDeviceEntity(id = 7, name = "Mac", deviceType = DeviceType.MACOS, ipAddress = "127.0.0.1"))
            val queue = MacLinkQueue(db.macLinkDao())
            queue.enqueue(7, SharedMacLink.parse("https://example.com/old")!!)
            val id = queue.enqueue(7, SharedMacLink.parse("https://example.com/new")!!)
            var sent = ""
            assertTrue(queue.open(7, id) { _, url -> sent = url; MacResult.Ok("") } is MacResult.Ok)
            assertEquals("https://example.com/new", sent)
            assertEquals(listOf("https://example.com/old"), db.macLinkDao().observe(7).first().map { it.url })
        } finally { db.close() }
    }

    @Test fun `authenticated controller opens via SSH and cancels an old target before sending`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = java.nio.file.Files.createTempDirectory("mac-link-ssh")
        val server = MacRemoteTest.FakeMac(dir.resolve("host.ser"))
        val controller = com.example.controller.UnifiedTvController(context,
            com.example.data.local.TvTokenStore.getInstance(context))
        val device = TvDeviceEntity(id = 91, name = "Mac", deviceType = DeviceType.MACOS,
            ipAddress = "127.0.0.1", port = server.port)
        try {
            controller.setActiveDevice(device)
            assertTrue(controller.loginMac("pablo", "secret") is MacResult.Ok)
            assertTrue(controller.openMacLink(91, "https://example.com/success") is MacResult.Ok)
            val encoded = android.util.Base64.encodeToString("https://example.com/success".toByteArray(), android.util.Base64.NO_WRAP)
            assertTrue(server.commands.any { it.first == "/usr/bin/osascript -l JavaScript -" && it.second.contains(encoded) })
            assertTrue(controller.openMacLink(92, "https://example.com/wrong") is MacResult.Failed)

            val client = controller.javaClass.getDeclaredField("macClient").let { it.isAccessible = true; it.get(controller) }
            val lock = client.javaClass.getDeclaredField("lock").let { it.isAccessible = true; it.get(client)!! }
            val locked = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val blocker = kotlin.concurrent.thread {
                synchronized(lock) { locked.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            }
            assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val pending = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                controller.openMacLink(91, "https://example.com/cancelled")
            }
            controller.disconnectCurrent()
            release.countDown()
            assertTrue(pending.await() is MacResult.Failed)
            blocker.join()
            val cancelled = android.util.Base64.encodeToString("https://example.com/cancelled".toByteArray(), android.util.Base64.NO_WRAP)
            assertFalse(server.commands.any { it.second.contains(cancelled) })
        } finally { controller.disconnectCurrent(); server.stop() }
    }

    @Test fun `queued link shares SSH with pinned media without retargeting playback or breaking volume`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = """{"id":"app:com.spotify.client:42","service":"SPOTIFY","processId":"42","title":"Track","token":"one","verified":true,"playback":"PAUSED","capabilities":["PLAY_PAUSE"]}"""
        val mediaActions = java.util.concurrent.atomic.AtomicInteger()
        val server = MacRemoteTest.FakeMac(java.nio.file.Files.createTempDirectory("shared-mac-ssh").resolve("host.ser"),
            scriptReply = { script ->
                when {
                    script.startsWith("var REQUEST = {op:\"discover\"") -> """{"sources":[$source]}"""
                    script.startsWith("var REQUEST = {op:\"action\"") -> { mediaActions.incrementAndGet(); """{"source":$source}""" }
                    script.startsWith("var REQUEST = {op:\"inspect\"") -> """{"source":$source}"""
                    else -> "44,false"
                }
            })
        val session = com.example.controller.RemoteSession.getInstance(context)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        suspend fun waitForMedia() = kotlinx.coroutines.withTimeout(5000) {
            while (session.macMedia.state.value.loading || session.macMedia.state.value.busy) {
                // Advance the paused Android clock through the media coordinator's 150 ms observation delay.
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
                kotlinx.coroutines.delay(10)
            }
        }
        try {
            val mac = TvDeviceEntity(id = 92, name = "Mac", deviceType = DeviceType.MACOS,
                ipAddress = "127.0.0.1", port = server.port)
            database.tvDeviceDao().insert(mac)
            session.selectDevice(mac)
            assertTrue(session.controller.loginMac("pablo", "secret") is MacResult.Ok)
            session.macMedia.refresh()
            waitForMedia()
            session.macMedia.select("app:com.spotify.client:42")
            assertTrue(session.macMedia.state.value.selectedSource!!.verified)
            val queue = MacLinkQueue(database.macLinkDao())
            queue.enqueue(mac.id, SharedMacLink.parse("https://youtu.be/queued")!!)
            assertTrue(queue.openNext(mac.id, session.controller::openMacLink) is MacResult.Ok)
            assertEquals("app:com.spotify.client:42", session.macMedia.state.value.selectedId)
            assertEquals(0, mediaActions.get())
            assertTrue(database.macLinkDao().observe(mac.id).first().isEmpty())
            session.sendKey(com.example.data.model.RemoteKey.PLAY_PAUSE)
            waitForMedia()
            assertEquals(1, mediaActions.get())
            assertEquals("app:com.spotify.client:42", session.macMedia.state.value.selectedId)
            assertEquals(MacResult.Ok("44,false"), session.controller.runMacMediaScript(com.example.network.MacScripts.readVolume) { true })
        } finally { session.clearDevice(); database.close(); server.stop() }
    }

    @Test fun `simultaneous next taps cannot dispatch the same head twice`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.tvDeviceDao().insert(TvDeviceEntity(id = 7, name = "Mac", deviceType = DeviceType.MACOS, ipAddress = "127.0.0.1"))
            val queue = MacLinkQueue(db.macLinkDao())
            queue.enqueue(7, SharedMacLink.parse("https://example.com/1")!!)
            queue.enqueue(7, SharedMacLink.parse("https://example.com/2")!!)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var sends = 0
            val first = async { queue.openNext(7) { _, _ -> sends++; entered.complete(Unit); release.await(); MacResult.Ok("") } }
            entered.await()
            val second = queue.openNext(7) { _, _ -> sends++; MacResult.Ok("") }
            assertTrue(second is MacResult.Failed)
            release.complete(Unit)
            first.await()
            assertEquals(1, sends)
            assertEquals("https://example.com/2", db.macLinkDao().first(7)!!.url)
        } finally { db.close() }
    }
}

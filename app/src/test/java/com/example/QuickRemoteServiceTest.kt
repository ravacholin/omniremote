package com.example

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.example.controller.RemoteSession
import com.example.data.local.AppDatabase
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.service.QuickRemoteManager
import com.example.service.QuickRemoteNotification
import com.example.service.QuickRemoteService
import com.example.sleep.SleepTimerManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuickRemoteServiceTest {
    @Test fun `old Mac media buttons are rejected when the source changes but volume still works`() {
        service = createService(connectAndRequest())
        session.selectedDevice.value = device.copy(deviceType = DeviceType.MACOS)
        val source = com.example.network.MacMediaSource("app:com.spotify.client:42", com.example.network.MacMediaService.SPOTIFY,
            "42", title = "Private song title", verified = true, capabilities = listOf("PLAY_PAUSE"))
        @Suppress("UNCHECKED_CAST")
        val mediaState = com.example.controller.MacMediaController::class.java.getDeclaredField("_state").let {
            it.isAccessible = true; it.get(session.macMedia) as kotlinx.coroutines.flow.MutableStateFlow<com.example.controller.MacMediaState>
        }
        mediaState.value = mediaState.value.copy(sources = listOf(source))
        val oldMedia = manager.actionIntent("play_pause")!!
        val oldVolume = manager.actionIntent("volume_up")!!
        session.macMedia.select(source.id)
        assertFalse(manager.valid(oldMedia))
        assertTrue(manager.valid(oldVolume))
        assertTrue(manager.valid(manager.actionIntent("play_pause")!!))
        val notification = QuickRemoteNotification.build(app, session, manager)!!
        val text = notification.extras.getCharSequence("android.text").toString()
        assertTrue(text.contains("Spotify"))
        assertFalse(text.contains("Private song title"))
    }

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var session: RemoteSession
    private lateinit var manager: QuickRemoteManager
    private lateinit var sockets: MutableList<RemoteSocket>
    private var service: QuickRemoteService? = null
    private val device = TvDeviceEntity(id = 7, name = "Living TV", deviceType = DeviceType.SAMSUNG_TIZEN,
        ipAddress = "127.0.0.1", macAddress = "AA:BB:CC:DD:EE:10", authToken = "12345678")

    @Before fun setup() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        session = RemoteSession.getInstance(app)
        manager = QuickRemoteManager.getInstance(app)
        sockets = installSockets(session.controller)
        idle()
    }
    @After fun cleanup() {
        manager.dismiss()
        service?.onDestroy()
        session.clearDevice()
        idle()
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true; (get(null) as? AppDatabase)?.close(); set(null, null)
        }
    }

    @Test fun `closing stays hidden through reconnect and device change until reopening`() {
        val start = connectAndRequest()
        val close = manager.actionIntent("close")!!
        service = createService(start)
        manager.onAppBackground(false)
        service!!.onStartCommand(close, 0, 2)
        assertFalse(manager.isRunning)
        assertEquals(ConnectionState.Disconnected, session.controller.connectionState.value)
        manager.refresh()
        assertNull(shadowOf(app).nextStartedService)
        // Opening enables it again, but only a real connection can show it.
        manager.onAppForeground()
        session.resumeConnection()
        sockets.last().connect()
        idle()
        assertNotNull(shadowOf(app).nextStartedService)
    }

    @Test fun `close while visible preserves the app remote and rotation does not resurrect notification`() {
        val start = connectAndRequest()
        service = createService(start)
        manager.dismiss()
        service!!.onDestroy(); service = null
        assertTrue(session.controller.connectionState.value is ConnectionState.Connected)
        manager.onAppBackground(true)
        manager.onAppForeground()
        manager.refresh()
        assertNull(shadowOf(app).nextStartedService)
        session.selectDevice(device.copy(id = 8, ipAddress = "127.0.0.2"))
        sockets.last().connect(); idle()
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `closing the mini remote in background preserves and allows extending custom Sleep`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // Own this fixture's Application/prefs; other Robolectric cases may have
        // initialized the production singleton with a previous fake Application.
        val timers = SleepTimerManager(app)
        timers.cancel()
        try {
            val scheduled = timers.scheduleFor(device, 45)
            service = createService(connectAndRequest())
            manager.onAppBackground(false)
            service!!.onStartCommand(manager.actionIntent("close"), 0, 2)
            assertFalse(manager.isRunning)
            assertEquals(ConnectionState.Disconnected, session.controller.connectionState.value)
            assertEquals(scheduled, timers.timer.value)
            val extended = timers.extend(scheduled.token)!!
            assertEquals(scheduled.deadlineElapsed + 900_000L, extended.deadlineElapsed)
            assertEquals(device.id, extended.deviceId)
            assertEquals(extended, SleepTimerManager(app).timer.value)
        } finally { timers.cancel() }
    }

    @Test fun `late service start after closing cannot restore notification`() {
        val start = connectAndRequest()
        manager.dismiss()
        val stale = createService(start)
        service = stale
        assertFalse(manager.isRunning)
        assertNull(shadowOf(stale).lastForegroundNotification)
    }

    @Test fun `old device buttons cannot control a newly selected TV`() {
        val start = connectAndRequest()
        service = createService(start)
        val oldPower = manager.actionIntent("power")!!
        session.selectDevice(device.copy(id = 8, ipAddress = "127.0.0.2"))
        sockets.last().connect(); idle()
        service!!.onStartCommand(oldPower, 0, 2)
        Thread.sleep(50); idle()
        assertTrue(sockets.last().commands().isEmpty())
        assertTrue(manager.isRunning)
    }

    @Test fun `notification controls share the app socket and playback state`() {
        service = createService(connectAndRequest())
        manager.onAppBackground(false)
        for ((index, action) in listOf("volume_up", "volume_down", "mute", "play_pause", "play_pause", "power").withIndex()) {
            service!!.onStartCommand(manager.actionIntent(action), 0, 2)
            await { sockets.single().commands().size == index + 1 }
        }
        await { sockets.single().commands().size == 6 }
        assertEquals(listOf("KEY_VOLUP", "KEY_VOLDOWN", "KEY_MUTE", "KEY_PAUSE", "KEY_PLAY", "KEY_POWER"),
            sockets.single().commands())
        assertTrue(session.playing.value)
        assertEquals(1, sockets.size)
    }

    @Test fun `denied notifications do not start an invisible foreground service`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.onAppForeground()
        session.selectDevice(device); sockets.single().connect(); idle()
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(session.controller.connectionState.value is ConnectionState.Connected)
        assertFalse(manager.isRunning)
    }

    @Test fun `blocked notification channel does not start a service`() {
        QuickRemoteNotification.ensureChannel(app)
        val notifications = app.getSystemService(NotificationManager::class.java)
        val channel = notifications.getNotificationChannel(QuickRemoteNotification.CHANNEL)
        channel.importance = NotificationManager.IMPORTANCE_NONE
        notifications.createNotificationChannel(channel)
        manager.onAppForeground()
        session.selectDevice(device); sockets.single().connect(); idle()
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `an old action delivered without an active service request cannot restore a session`() {
        val stale = Intent(app, QuickRemoteService::class.java).setAction("power")
            .putExtra("token", "previous-process")
        service = createService(stale)
        assertFalse(manager.isRunning)
        assertNull(shadowOf(service!!).lastForegroundNotification)
        assertTrue(sockets.isEmpty())
    }

    @Test fun `notification supplies all six accessible buttons and close on swipe`() {
        service = createService(connectAndRequest())
        val notification = shadowOf(service!!).lastForegroundNotification!!
        assertEquals("Living TV", notification.extras.getCharSequence("android.title").toString())
        assertNotNull(notification.deleteIntent)
        assertNotNull(notification.contentIntent)
        val view = notification.bigContentView.apply(app, FrameLayout(app))
        for ((id, label) in listOf(R.id.quick_volume_up to "Subir volumen", R.id.quick_volume_down to "Bajar volumen",
            R.id.quick_mute to "Silenciar", R.id.quick_play_pause to "Reproducir o pausar",
            R.id.quick_power to "Encender o apagar", R.id.quick_close to "Cerrar mini mando")) {
            val button = view.findViewById<View>(id)
            assertEquals(label, button.contentDescription.toString())
            assertTrue(button.hasOnClickListeners())
        }
        notification.deleteIntent.send()
        val close = shadowOf(app).nextStartedService!!
        service!!.onStartCommand(close, 0, 3)
        assertFalse(manager.isRunning)
    }

    @Test @Config(sdk = [24]) fun `compact and expanded controls work on Android 7`() {
        service = createService(connectAndRequest())
        val notification = shadowOf(service!!).lastForegroundNotification!!
        assertNotNull(notification.contentView.apply(app, FrameLayout(app)).findViewById<View>(R.id.quick_close))
        assertNotNull(notification.bigContentView.apply(app, FrameLayout(app)).findViewById<View>(R.id.quick_power))
    }

    @Test @Config(sdk = [33]) fun `notification is published on Android 13`() {
        service = createService(connectAndRequest())
        assertNotNull(shadowOf(service!!).lastForegroundNotification)
    }

    @Test fun `closing an older displayed device still closes the current mini remote`() {
        service = createService(connectAndRequest())
        val close = manager.actionIntent("close")!!
        session.selectDevice(device.copy(id = 8, ipAddress = "127.0.0.2"))
        sockets.last().connect(); idle()
        service!!.onStartCommand(close, 0, 2)
        assertFalse("Closing belongs to the notification session, not its previous device", manager.isRunning)
        assertTrue(sockets.last().commands().isEmpty())
    }

    @Test fun `blocking a running channel stops its service and background transport`() {
        service = createService(connectAndRequest())
        manager.onAppBackground(false)
        app.sendBroadcast(Intent(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
            .putExtra(NotificationManager.EXTRA_NOTIFICATION_CHANNEL_ID, "quick_remote")
            .putExtra(NotificationManager.EXTRA_BLOCKED_STATE, true))
        idle()
        assertFalse(manager.isRunning)
        assertEquals(ConnectionState.Disconnected, session.controller.connectionState.value)
    }

    @Test fun `blocking all app notifications stops the mini remote`() {
        service = createService(connectAndRequest())
        app.sendBroadcast(Intent(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
            .putExtra(NotificationManager.EXTRA_BLOCKED_STATE, true))
        idle()
        assertFalse(manager.isRunning)
    }

    @Test fun `removing the last device does not count as the user closing the mini remote`() {
        service = createService(connectAndRequest())
        session.clearDevice()
        idle()
        assertFalse(manager.isRunning)
        session.selectDevice(device.copy(id = 8, ipAddress = "127.0.0.2"))
        sockets.last().connect(); idle()
        assertNotNull("Connecting a newly added device in the same opening should show its mini remote",
            shadowOf(app).nextStartedService)
    }

    private fun connectAndRequest(): Intent {
        manager.onAppForeground()
        session.selectDevice(device)
        sockets.single().connect(); idle()
        return shadowOf(app).nextStartedService ?: error("Connected device must request mini remote service")
    }
    private fun createService(intent: Intent): QuickRemoteService {
        val s = Robolectric.buildService(QuickRemoteService::class.java).create().get()
        s.onStartCommand(intent, 0, 1)
        idle()
        return s
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3000
        while (!done() && System.currentTimeMillis() < deadline) { idle(); Thread.sleep(10) }
        assertTrue("Timed out waiting for remote controls", done())
    }
}

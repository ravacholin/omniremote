package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.controller.RemoteSession
import com.example.data.local.AppDatabase
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.share.MacLinksState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MacShareStateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var db: AppDatabase
    private lateinit var state: MacLinksState
    private val mac = TvDeviceEntity(id = 7, name = "Mac", deviceType = DeviceType.MACOS, ipAddress = "127.0.0.1")
    private fun share(url: String = "https://example.com/watch") = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)

    @Before fun setup() = runBlocking {
        resetDb()
        context.deleteDatabase("omni_remote_database.db")
        db = AppDatabase.getInstance(context)
        db.tvDeviceDao().insert(mac)
        db.tvDeviceDao().insert(mac.copy(id = 8, deviceType = DeviceType.ANDROID_TV))
        state = MacLinksState(context, RemoteSession.getInstance(context), scope)
    }

    @After fun cleanup() {
        RemoteSession.getInstance(context).clearDevice()
        val job = scope.coroutineContext[Job]!!
        scope.cancel()
        awaitQuickRemoteJobs(listOf(job))
        resetDb()
    }

    @Test fun `save works offline does not select a device and rejects a TV destination`() = runBlocking {
        state.receive(share())
        state.submit(8, open = false)
        settle()
        assertTrue(db.macLinkDao().observe(8).first().isEmpty())
        assertNotNull(state.pendingShare.value)
        assertNotNull(state.error.value)
        state.submit(7, open = false)
        settle()
        assertEquals("https://example.com/watch", db.macLinkDao().first(7)!!.url)
        assertNull(state.pendingShare.value)
        assertNull(RemoteSession.getInstance(context).selectedDevice.value)
    }

    @Test fun `failed open can be retried or saved without duplicating its queued link`() = runBlocking {
        state.receive(share())
        state.submit(7, open = true)
        settle()
        assertNotNull(state.error.value)
        assertNotNull(state.pendingShare.value)
        state.submit(7, open = true)
        settle()
        assertEquals(1, db.macLinkDao().observe(7).first().size)
        val saved = Bundle()
        state.saveState(saved)
        val restored = MacLinksState(context, RemoteSession.getInstance(context), scope)
        restored.restoreState(saved)
        restored.submit(7, open = false)
        withTimeout(5000) { while (restored.busy.value) { shadowOf(Looper.getMainLooper()).idle(); delay(10) } }
        assertNull(restored.pendingShare.value)
        assertEquals(1, db.macLinkDao().observe(7).first().size)
    }

    @Test fun `receiving a newer share while saving cannot dismiss or replace it`() = runBlocking {
        state.receive(share("https://example.com/first"))
        state.submit(7, open = false)
        state.receive(share("https://example.com/second"))
        settle()
        assertEquals("https://example.com/second", state.pendingShare.value!!.link!!.url)
        assertEquals("https://example.com/first", db.macLinkDao().first(7)!!.url)
    }

    @Test fun `restoring an old Activity snapshot cannot revive a consumed request in the retained model`() {
        state.receive(share())
        val snapshot = Bundle()
        state.saveState(snapshot)
        state.dismissShare()
        state.restoreState(snapshot)
        assertNull(state.pendingShare.value)
    }

    @Test fun `new share closes old queue so its result cannot leak into the share dialog`() {
        state.showQueue(7)
        state.receive(share())
        assertNull(state.queueDeviceId.value)
    }

    private suspend fun settle() = withTimeout(5000) {
        while (state.busy.value) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
    }
    private fun resetDb() {
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true; (get(null) as? AppDatabase)?.close(); set(null, null)
        }
    }
}

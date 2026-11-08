package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.controller.RemoteSession
import com.example.data.local.AppDatabase
import com.example.ui.MainViewModel
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MacShareLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun share(url: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)

    @After fun cleanup() {
        RemoteSession.getInstance(context).clearDevice()
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true; (get(null) as? AppDatabase)?.close(); set(null, null)
        }
    }

    @Test fun `activity receives new shares and restores a pending request without receiving it twice`() {
        // Avoid ActivityController.recreate's window-focus assumption for an unattached Compose window.
        var activity = Robolectric.buildActivity(MainActivity::class.java, share("https://example.com/one")).create().start().resume()
        var model = ViewModelProvider(activity.get())[MainViewModel::class.java]
        fun saveAndDestroy(): Bundle {
            val state = Bundle()
            val job = model.viewModelScope.coroutineContext[Job]!!
            activity.pause().saveInstanceState(state).stop().destroy()
            awaitQuickRemoteJobs(listOf(job))
            return state
        }
        try {
            val firstId = model.macLinks.pendingShare.value!!.id
            assertEquals("https://example.com/one", model.macLinks.pendingShare.value!!.link!!.url)
            val saved = saveAndDestroy()
            activity = Robolectric.buildActivity(MainActivity::class.java, share("https://example.com/one"))
                .create(saved).start().resume()
            model = ViewModelProvider(activity.get())[MainViewModel::class.java]
            assertEquals(firstId, model.macLinks.pendingShare.value!!.id)
            activity.newIntent(share("https://example.com/two"))
            assertEquals("https://example.com/two", model.macLinks.pendingShare.value!!.link!!.url)
            assertNotEquals(firstId, model.macLinks.pendingShare.value!!.id)
            model.macLinks.dismissShare()
            val consumed = saveAndDestroy()
            activity = Robolectric.buildActivity(MainActivity::class.java, share("https://example.com/two"))
                .create(consumed).start().resume()
            model = ViewModelProvider(activity.get())[MainViewModel::class.java]
            assertNull(model.macLinks.pendingShare.value)
        } finally {
            val job = model.viewModelScope.coroutineContext[Job]!!
            activity.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
            awaitQuickRemoteJobs(listOf(job))
        }
    }

    @Test fun `Android share sheet resolves OmniRemote for web links`() {
        val matches = context.packageManager.queryIntentActivities(share("https://example.com"), 0)
        assertTrue(matches.any { it.activityInfo.name == MainActivity::class.java.name })
    }
}

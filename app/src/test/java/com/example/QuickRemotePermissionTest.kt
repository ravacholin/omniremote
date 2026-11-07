package com.example

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ui.MainViewModel
import kotlinx.coroutines.Job
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** The transitive legacy Fragment library must not intercept ComponentActivity's permission result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickRemotePermissionTest {
    @Test fun `notification permission denial reaches the actual activity result callback`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Do not attach the animated Compose screen: this test only exercises the activity callback.
        val activityController = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = activityController.get()
        val modelJob = ViewModelProvider(activity)[MainViewModel::class.java].viewModelScope.coroutineContext[Job]
        try {
            @Suppress("UNCHECKED_CAST")
            val launcher = MainActivity::class.java.getDeclaredField("notificationPermission").let {
                it.isAccessible = true; it.get(activity) as ActivityResultLauncher<String>
            }
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            val request = shadowOf(activity).lastRequestedPermission
            assertArrayEquals(arrayOf("android.permission.POST_NOTIFICATIONS"), request.requestedPermissions)
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                intArrayOf(PackageManager.PERMISSION_DENIED))
            assertEquals(app.getString(R.string.quick_permission_denied), ShadowToast.getTextOfLatestToast())
        } finally {
            activityController.pause().stop().destroy()
            awaitQuickRemoteJobs(listOfNotNull(modelJob))
            AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
                isAccessible = true; (get(null) as? AppDatabase)?.close(); set(null, null)
            }
        }
    }
}

package com.example

import android.os.Looper
import kotlinx.coroutines.Job
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf

/** Finish cancellation/Room transaction cleanup before closing the test database. */
internal fun awaitQuickRemoteJobs(jobs: List<Job>) {
    val deadline = System.currentTimeMillis() + 5000
    while (jobs.any { !it.isCompleted } && System.currentTimeMillis() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(5)
    }
    assertTrue("ViewModel jobs must finish before the database is closed", jobs.all { it.isCompleted })
}

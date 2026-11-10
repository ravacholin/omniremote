package com.example.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TvScannerCancellationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `invalid subnet fails before starting any probes`() = runBlocking {
        val probes = AtomicInteger()
        val scanner = TvScanner(context) { _, _, _ -> probes.incrementAndGet(); false }
        val failure = runCatching { scanner.scanLocalNetwork("192.168.999.") }.exceptionOrNull()
        assertTrue("Malformed IPv4 subnet must be rejected", failure is IllegalArgumentException)
        assertEquals(0, probes.get())
    }

    @Test fun `cancelling a scan prevents subsequent port probes for that host`() = runBlocking {
        val scanJob = Job()
        val probes = ConcurrentHashMap<String, AtomicInteger>()
        val canceller = java.util.concurrent.atomic.AtomicBoolean()
        val cancellationFinished = java.util.concurrent.CountDownLatch(1)
        val scanner = TvScanner(context) { ip, _, _ ->
            probes.computeIfAbsent(ip) { AtomicInteger() }.incrementAndGet()
            // Concurrent cancel() calls may return while the first call is still notifying
            // child jobs. Make every probe return only once that propagation has completed.
            if (canceller.compareAndSet(false, true)) {
                try { scanJob.cancel() } finally { cancellationFinished.countDown() }
            } else {
                check(cancellationFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            false
        }
        try {
            withContext(scanJob) { scanner.scanLocalNetwork("192.168.1.") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(probes.isNotEmpty())
        assertTrue("Cancellation must stop before trying the next port", probes.values.all { it.get() == 1 })
    }
}

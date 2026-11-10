package com.example

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.example.network.DiscoveredTv
import com.example.network.NsdTvDiscoveryService
import com.example.network.TvScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [NsdDiscoveryLifecycleTest.RecordingNsdManager::class])
class NsdDiscoveryLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `old resolution cannot publish or release the new resolver after a restart`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scanner = object : TvScanner(context, portProbe = { _, _, _ ->
            entered.countDown(); release.await(5, TimeUnit.SECONDS); false
        }) {
            override suspend fun scanLocalNetwork(customSubnet: String?, onProgress: ((Int, Int) -> Unit)?,
                onDeviceFound: ((DiscoveredTv) -> Unit)?): List<DiscoveredTv> = awaitCancellation()
        }
        val service = service(scanner)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            service.startDiscovery()
            announce("_airplay._tcp", info("Old TV", "_airplay._tcp", "192.168.1.8"))
            val old = RecordingNsdManager.resolves.poll(2, TimeUnit.SECONDS)!!
            val pending = executor.submit { old.second.onServiceResolved(old.first) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            service.stopDiscovery()
            service.startDiscovery()
            announce("_androidtvremote2._tcp", info("New TV", "_androidtvremote2._tcp", "192.168.1.9"))
            val current = RecordingNsdManager.resolves.poll(2, TimeUnit.SECONDS)!!
            release.countDown()
            pending.get(2, TimeUnit.SECONDS)
            assertTrue("A stopped scan must not populate the new results", service.discoveredTvs.value.isEmpty())
            assertTrue("The old callback must not release the current resolver", resolving(service))
            current.second.onServiceResolved(current.first)
            assertEquals(listOf("192.168.1.9"), service.discoveredTvs.value.map { it.ipAddress })
        } finally { release.countDown(); executor.shutdownNow(); cleanup(service) }
    }

    @Test fun `fallback publication waiting on the service lock is discarded after restart`() {
        val entered = CountDownLatch(1)
        val report = AtomicReference<((DiscoveredTv) -> Unit)?>()
        val scanner = object : TvScanner(context) {
            override suspend fun scanLocalNetwork(customSubnet: String?, onProgress: ((Int, Int) -> Unit)?,
                onDeviceFound: ((DiscoveredTv) -> Unit)?): List<DiscoveredTv> {
                report.set(onDeviceFound); entered.countDown(); return awaitCancellation()
            }
        }
        val service = service(scanner)
        var worker: Thread? = null
        try {
            service.startDiscovery()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val oldReport = report.get()!!
            synchronized(service) {
                worker = kotlin.concurrent.thread {
                    oldReport(DiscoveredTv("Old TV", "192.168.1.8", com.example.data.model.DeviceType.ANDROID_TV, 6466))
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (worker!!.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, worker!!.state)
                service.stopDiscovery()
                service.startDiscovery()
            }
            worker!!.join(2000)
            assertFalse(worker!!.isAlive)
            assertTrue("Generation must be checked inside publication's lock", service.discoveredTvs.value.isEmpty())
        } finally { worker?.join(2000); cleanup(service) }
    }

    private fun service(scanner: TvScanner) = NsdTvDiscoveryService(context).also {
        it.javaClass.getDeclaredField("fallbackScanner").apply { isAccessible = true; set(it, scanner) }
        RecordingNsdManager.discoveries.clear()
        RecordingNsdManager.resolves.clear()
    }
    private fun resolving(service: NsdTvDiscoveryService) = service.javaClass.getDeclaredField("isResolving").let {
        it.isAccessible = true; it.getBoolean(service)
    }
    private fun announce(type: String, info: NsdServiceInfo) {
        RecordingNsdManager.discoveries.last { it.first == type }.second.onServiceFound(info)
    }
    private fun info(name: String, type: String, ip: String) = NsdServiceInfo().apply {
        serviceName = name; serviceType = type; port = 6466; host = InetAddress.getByName(ip)
    }
    private fun cleanup(service: NsdTvDiscoveryService) {
        service.stopDiscovery()
        (service.javaClass.getDeclaredField("scope").let { it.isAccessible = true; it.get(service) } as CoroutineScope).cancel()
    }

    @Implements(NsdManager::class)
    class RecordingNsdManager {
        @Implementation fun discoverServices(type: String, protocol: Int, listener: NsdManager.DiscoveryListener) {
            discoveries += type to listener
        }
        @Implementation fun stopServiceDiscovery(listener: NsdManager.DiscoveryListener) = Unit
        @Implementation fun resolveService(info: NsdServiceInfo, listener: NsdManager.ResolveListener) {
            resolves.offer(info to listener)
        }
        companion object {
            val discoveries = java.util.concurrent.CopyOnWriteArrayList<Pair<String, NsdManager.DiscoveryListener>>()
            val resolves = LinkedBlockingQueue<Pair<NsdServiceInfo, NsdManager.ResolveListener>>()
        }
    }
}

package com.example

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.controller.RemoteSession
import com.example.data.local.AppDatabase
import com.example.network.DiscoveredTv
import com.example.network.TvScanner
import com.example.ui.MainViewModel
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkScanLifecycleTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private var model: MainViewModel? = null

    @After fun cleanup() {
        model?.closeScanResultsScreen()
        RemoteSession.getInstance(app).clearDevice()
        val job = model?.viewModelScope?.coroutineContext?.get(Job)
        store.clear()
        awaitQuickRemoteJobs(listOfNotNull(job))
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true; (get(null) as? AppDatabase)?.close(); set(null, null)
        }
    }

    @Test fun `cancelled scanner restores idle state and allows a retry`() {
        var calls = 0
        val vm = create { _, _ ->
            calls++
            if (calls == 1) throw CancellationException("network search cancelled")
            emptyList()
        }
        vm.startNetworkScan("192.168.1.")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Cancellation must reset the spinner", vm.isScanning.value)
        vm.startNetworkScan("192.168.1.")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, calls)
        assertFalse(vm.isScanning.value)
    }

    @Test fun `closing the results screen cancels active scan and ignores late progress`() {
        var cancelled = false
        var progress: ((Int, Int) -> Unit)? = null
        val vm = create { _, report ->
            progress = report
            try { awaitCancellation() } finally { cancelled = true }
        }
        vm.startNetworkScan("192.168.1.")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(vm.isScanning.value)
        vm.closeScanResultsScreen()
        shadowOf(Looper.getMainLooper()).idle()
        val stoppedProgress = vm.scanProgress.value
        progress?.invoke(253, 254)
        assertTrue("Closing must cancel the scan", cancelled)
        assertFalse(vm.isScanning.value)
        assertEquals("Late callbacks must not update a stopped scan", stoppedProgress, vm.scanProgress.value)
    }

    @Test fun `closing device manager cancels the manual subnet scan`() {
        var cancelled = false
        val vm = create { _, _ ->
            try { awaitCancellation() } finally { cancelled = true }
        }
        vm.setDeviceManagerOpen(true)
        vm.startNetworkScan("192.168.1.")
        shadowOf(Looper.getMainLooper()).idle()
        vm.setDeviceManagerOpen(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Closing the manual scan's own dialog must cancel it", cancelled)
        assertFalse(vm.isScanning.value)
    }

    @Test fun `network failure shows an error and allows retry`() {
        var calls = 0
        val vm = create { _, _ ->
            if (++calls == 1) throw IllegalStateException("network unavailable")
            emptyList()
        }
        vm.startNetworkScan("192.168.1.")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(vm.isScanning.value)
        assertTrue(vm.scanStatusMessage.value.contains("No se pudo"))
        vm.startNetworkScan("192.168.1.")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, calls)
        assertFalse(vm.isScanning.value)
    }

    private fun create(scan: suspend (String?, ((Int, Int) -> Unit)?) -> List<DiscoveredTv>): MainViewModel {
        val scanner = object : TvScanner(app) {
            override suspend fun scanLocalNetwork(customSubnet: String?, onProgress: ((Int, Int) -> Unit)?,
                onDeviceFound: ((DiscoveredTv) -> Unit)?): List<DiscoveredTv> = scan(customSubnet, onProgress)
        }
        return MainViewModel(app, scanner).also { model = it; store.put("model", it) }
    }
}

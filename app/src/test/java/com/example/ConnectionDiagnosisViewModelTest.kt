package com.example

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.controller.UnifiedTvController
import com.example.data.local.AppDatabase
import com.example.data.model.DeviceType
import com.example.data.model.ConnectionState
import com.example.data.model.TvDeviceEntity
import com.example.network.ConnectionDiagnosticReport
import com.example.network.DiagnosticAction
import com.example.network.DiagnosticEnvironment
import com.example.network.AuthorizationEvidence
import com.example.network.DiscoveredTv
import com.example.ui.MainViewModel
import com.example.ui.components.ConnectionDiagnosisUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitCancellation
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
class ConnectionDiagnosisViewModelTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private lateinit var model: MainViewModel
    private val tv = TvDeviceEntity(id = 7, name = "Sala", deviceType = DeviceType.SAMSUNG_TIZEN, ipAddress = "127.0.0.1")

    @Before fun setup() {
        resetDatabase()
        application.deleteDatabase("omni_remote_database.db")
        model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[MainViewModel::class.java]
        active().value = tv
    }

    @After fun teardown() {
        model.closeConnectionDiagnosis()
        field<UnifiedTvController>("controller").disconnectCurrent()
        val job = model.viewModelScope.coroutineContext[Job]
        store.clear()
        runBlocking {
            withTimeout(5000) {
                while (job?.isCompleted == false) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
            }
        }
        resetDatabase()
    }

    @Test fun `closing cancels checks and cannot republish their results`() {
        usePendingNetwork()
        model.diagnoseConnection()
        val job = field<Job>("diagnosticJob")
        model.closeConnectionDiagnosis()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(job.isCancelled)
        assertNull(model.connectionDiagnosis.value)
    }

    @Test fun `authorization action from an old report cannot pair an already connected TV`() {
        val android = tv.copy(deviceType = DeviceType.ANDROID_TV)
        active().value = android
        field<MutableStateFlow<ConnectionDiagnosisUiState?>>("_connectionDiagnosis").value =
            ConnectionDiagnosisUiState(tv.name, isRunning = false, report =
                ConnectionDiagnosticReport(tv.id, tv.ipAddress, emptyList(), listOf(DiagnosticAction.AUTHORIZE)))
        val controller = field<UnifiedTvController>("controller")
        @Suppress("UNCHECKED_CAST")
        val state = UnifiedTvController::class.java.getDeclaredField("_connectionState").let {
            it.isAccessible = true; it.get(controller) as MutableStateFlow<ConnectionState>
        }
        state.value = ConnectionState.Connected(android.name, android.ipAddress)
        model.performDiagnosticAction(DiagnosticAction.AUTHORIZE)
        assertFalse(model.isAndroidTvPairingDialogOpen.value)
    }

    @Test fun `an old report cannot act on a newly selected device or changed address`() {
        for (other in listOf(tv.copy(id = 8), tv.copy(ipAddress = "127.0.0.2"))) {
            report()
            active().value = other
            model.performDiagnosticAction(DiagnosticAction.MANAGE_DEVICE)
            assertFalse(model.isDeviceManagerOpen.value)
        }
    }

    @Test fun `an action absent from the report cannot initiate pairing`() {
        report()
        model.performDiagnosticAction(DiagnosticAction.AUTHORIZE)
        assertFalse(model.isAndroidTvPairingDialogOpen.value)
        assertEquals(null, model.connectionDiagnosis.value?.message)
    }

    @Test fun `manual review closes diagnosis and opens saved devices`() {
        report()
        model.performDiagnosticAction(DiagnosticAction.MANAGE_DEVICE)
        assertTrue(model.isDeviceManagerOpen.value)
        assertNull(model.connectionDiagnosis.value)
    }

    @Test fun `a stale wake action preserves a Samsung permission prompt`() {
        usePendingNetwork()
        val controller = field<UnifiedTvController>("controller")
        @Suppress("UNCHECKED_CAST")
        val state = UnifiedTvController::class.java.getDeclaredField("_connectionState").let {
            it.isAccessible = true; it.get(controller) as MutableStateFlow<ConnectionState>
        }
        state.value = ConnectionState.WaitingAuth(tv.name, "Permití en la TV")
        field<MutableStateFlow<ConnectionDiagnosisUiState?>>("_connectionDiagnosis").value =
            ConnectionDiagnosisUiState(tv.name, isRunning = false, report =
                ConnectionDiagnosticReport(tv.id, tv.ipAddress, emptyList(), listOf(DiagnosticAction.WAKE)))
        model.performDiagnosticAction(DiagnosticAction.WAKE)
        assertNull(model.powerOnStatusMessage.value)
        assertNotNull("Refresh diagnosis instead of dispatching WOL", model.connectionDiagnosis.value)
        assertTrue(model.connectionState.value is ConnectionState.WaitingAuth)
    }

    @Test fun `selecting another device cancels its predecessors diagnosis`() {
        usePendingNetwork()
        model.diagnoseConnection()
        val job = field<Job>("diagnosticJob")
        model.selectDevice(tv.copy(id = 8, ipAddress = "127.0.0.2"))
        assertTrue(job.isCancelled)
        assertNull(model.connectionDiagnosis.value)
    }

    private fun usePendingNetwork() {
        val pending = object : DiagnosticEnvironment {
            override fun localIp() = "192.168.1.2"
            override suspend fun responds(device: TvDeviceEntity): Boolean = awaitCancellation()
            override suspend fun hardwareAddress(device: TvDeviceEntity): String? = null
            override suspend fun discover(): List<DiscoveredTv> = awaitCancellation()
            override fun authorization(device: TvDeviceEntity) = AuthorizationEvidence(ConnectionState.Disconnected, true)
        }
        MainViewModel::class.java.getDeclaredField("diagnosticEnvironment").apply { isAccessible = true; set(model, pending) }
    }

    private fun report() {
        active().value = tv
        field<MutableStateFlow<ConnectionDiagnosisUiState?>>("_connectionDiagnosis").value =
            ConnectionDiagnosisUiState(tv.name, isRunning = false, report =
                ConnectionDiagnosticReport(tv.id, tv.ipAddress, emptyList(), listOf(DiagnosticAction.MANAGE_DEVICE)))
    }

    private fun active() = field<MutableStateFlow<TvDeviceEntity?>>("_activeDevice")
    @Suppress("UNCHECKED_CAST") private fun <T> field(name: String): T =
        MainViewModel::class.java.getDeclaredField(name).let { it.isAccessible = true; it.get(model) as T }
    private fun resetDatabase() {
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true; (get(null) as? AppDatabase)?.close(); set(null, null)
        }
    }
}

package com.example

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.controller.RemoteSession
import com.example.data.local.AppDatabase
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.ui.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceActionLifecycleTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private lateinit var model: MainViewModel
    private lateinit var session: RemoteSession
    private val tv = TvDeviceEntity(id = 71, name = "FlowBox", deviceType = DeviceType.ANDROID_TV,
        ipAddress = "127.0.0.1", port = 6466)

    @Before fun setup() {
        model = MainViewModel(app)
        store.put("model", model)
        session = RemoteSession.getInstance(app)
    }

    @After fun cleanup() {
        model.closeAndroidTvPairingDialog()
        session.clearDevice()
        val job = model.viewModelScope.coroutineContext[Job]!!
        store.clear()
        awaitQuickRemoteJobs(listOf(job))
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true; (get(null) as? AppDatabase)?.close(); set(null, null)
        }
    }

    @Test fun `switching TV dismisses the previous PIN and busy state`() {
        model.selectDevice(tv)
        model.startAndroidTvPairing()
        assertTrue(model.isAndroidTvPairingDialogOpen.value)
        model.selectDevice(tv.copy(id = 72, ipAddress = "127.0.0.2"))
        assertFalse("The old PIN must not be submitted to the new TV", model.isAndroidTvPairingDialogOpen.value)
        assertFalse(model.isPairingInProgress.value)
        assertNull(model.pairingErrorMessage.value)
    }

    @Test fun `changing IP on the same TV dismisses its old PIN`() {
        model.selectDevice(tv)
        model.startAndroidTvPairing()
        model.selectDevice(tv.copy(ipAddress = "127.0.0.2"))
        assertFalse(model.isAndroidTvPairingDialogOpen.value)
        assertFalse(model.isPairingInProgress.value)
    }

    @Test fun `reselecting the same endpoint preserves its PIN exchange`() {
        model.selectDevice(tv)
        model.startAndroidTvPairing()
        model.selectDevice(tv.copy(name = "Living"))
        assertTrue(model.isAndroidTvPairingDialogOpen.value)
        assertTrue(model.isPairingInProgress.value)
        assertTrue(model.connectionState.value is ConnectionState.WaitingAuth)
    }

    @Test fun `Android pairing does not replace a Mac connection state`() {
        val mac = tv.copy(deviceType = DeviceType.MACOS, port = 22)
        model.selectDevice(mac)
        val state = model.connectionState.value
        model.startAndroidTvPairing()
        assertFalse(model.isAndroidTvPairingDialogOpen.value)
        assertSame(state, model.connectionState.value)
    }

    @Test fun `controller rejects Android pairing on another device type`() {
        session.selectDevice(tv.copy(deviceType = DeviceType.MACOS, port = 22))
        val state = session.controller.connectionState.value
        var rejected = false
        session.controller.startAndroidTvPairing({ fail("Must not request a PIN") }) { success, _ ->
            rejected = !success
        }
        assertTrue("Reject before touching the existing connection", rejected)
        assertSame(state, session.controller.connectionState.value)
    }

    @Test fun `saving a previous TVs MAC must not wake the new selection or close its dialog`() = runBlocking {
        saveMacWhileSelectionChanges(returnToOriginal = false)
    }

    @Test fun `returning to the original TV does not let a pending MAC save close its new dialog`() = runBlocking {
        saveMacWhileSelectionChanges(returnToOriginal = true)
    }

    private suspend fun saveMacWhileSelectionChanges(returnToOriginal: Boolean) {
        val db = AppDatabase.getInstance(app)
        db.tvDeviceDao().insert(tv)
        model.selectDevice(tv)
        model.openMacConfigDialog(tv)
        val initialJobs = model.viewModelScope.coroutineContext[Job]!!.children.toSet()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transaction = CoroutineScope(Dispatchers.IO).launch {
            db.withTransaction { entered.complete(Unit); release.await() }
        }
        entered.await()
        val other = tv.copy(id = 72, name = "Other TV", ipAddress = "127.0.0.2")
        try {
            model.updateDeviceMac(tv, "AA:BB:CC:DD:EE:10", andPowerOn = !returnToOriginal)
            model.selectDevice(other)
            if (returnToOriginal) model.selectDevice(tv)
            model.openMacConfigDialog(if (returnToOriginal) tv else other)
        } finally {
            release.complete(Unit)
            transaction.join()
        }
        awaitQuickRemoteJobs(model.viewModelScope.coroutineContext[Job]!!.children.filter { it !in initialJobs }.toList())
        assertEquals("AA:BB:CC:DD:EE:10", db.tvDeviceDao().getDeviceById(tv.id)!!.macAddress)
        assertTrue("A stale completion must not dismiss the new configuration", model.showMacConfigDialog.value)
        assertEquals(if (returnToOriginal) tv.id else other.id, model.macConfigDevice.value?.id)
        val wakeJob = RemoteSession::class.java.getDeclaredField("wakeJob").let {
            it.isAccessible = true; it.get(session)
        }
        assertNull("A stale completion must not start a wake operation", wakeJob)
    }

    @Test fun `saving MAC without waking updates the selected TV and closes its own dialog`() = runBlocking {
        val db = AppDatabase.getInstance(app)
        db.tvDeviceDao().insert(tv)
        model.selectDevice(tv)
        model.openMacConfigDialog(tv)
        val initialJobs = model.viewModelScope.coroutineContext[Job]!!.children.toSet()
        model.updateDeviceMac(tv, "aa-bb-cc-dd-ee-10", andPowerOn = false)
        awaitQuickRemoteJobs(model.viewModelScope.coroutineContext[Job]!!.children.filter { it !in initialJobs }.toList())
        assertEquals("AA:BB:CC:DD:EE:10", db.tvDeviceDao().getDeviceById(tv.id)!!.macAddress)
        assertEquals("AA:BB:CC:DD:EE:10", model.activeDevice.value?.macAddress)
        assertFalse(model.showMacConfigDialog.value)
    }

    @Test fun `saving a nonselected TVs MAC closes its own dialog without changing selection`() = runBlocking {
        val db = AppDatabase.getInstance(app)
        db.tvDeviceDao().insert(tv)
        val other = tv.copy(id = 72, ipAddress = "127.0.0.2")
        model.selectDevice(other)
        model.openMacConfigDialog(tv)
        val initialJobs = model.viewModelScope.coroutineContext[Job]!!.children.toSet()
        model.updateDeviceMac(tv, "AA:BB:CC:DD:EE:10", andPowerOn = false)
        awaitQuickRemoteJobs(model.viewModelScope.coroutineContext[Job]!!.children.filter { it !in initialJobs }.toList())
        assertEquals("AA:BB:CC:DD:EE:10", db.tvDeviceDao().getDeviceById(tv.id)!!.macAddress)
        assertEquals(other.id, model.activeDevice.value?.id)
        assertFalse("Editing a saved device does not require selecting it", model.showMacConfigDialog.value)
    }

    @Test fun `save and wake for a nonselected TV sends its magic packet to the edited endpoint`() = runBlocking {
        val db = AppDatabase.getInstance(app)
        db.tvDeviceDao().insert(tv)
        val other = tv.copy(id = 72, ipAddress = "127.0.0.2")
        model.selectDevice(other)
        model.openMacConfigDialog(tv)
        DatagramSocket(9, InetAddress.getByName("127.0.0.1")).use { receiver ->
            receiver.soTimeout = 2000
            val initialJobs = model.viewModelScope.coroutineContext[Job]!!.children.toSet()
            model.updateDeviceMac(tv, "AA:BB:CC:DD:EE:10", andPowerOn = true)
            val saveJobs = model.viewModelScope.coroutineContext[Job]!!.children.filter { it !in initialJobs }.toList()
            awaitQuickRemoteJobs(saveJobs)
            val packet = DatagramPacket(ByteArray(256), 256)
            receiver.receive(packet)
            assertEquals(102, packet.length)
            assertArrayEquals(byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(),
                0xdd.toByte(), 0xee.toByte(), 0x10), packet.data.copyOfRange(6, 12))
            assertEquals(other.id, model.activeDevice.value?.id)
            assertFalse(model.showMacConfigDialog.value)
        }
    }

    @Test fun `reopening the same MAC dialog is not dismissed by the earlier save`() = runBlocking {
        val db = AppDatabase.getInstance(app)
        db.tvDeviceDao().insert(tv)
        model.selectDevice(tv)
        model.openMacConfigDialog(tv)
        val initialJobs = model.viewModelScope.coroutineContext[Job]!!.children.toSet()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transaction = CoroutineScope(Dispatchers.IO).launch {
            db.withTransaction { entered.complete(Unit); release.await() }
        }
        entered.await()
        try {
            model.updateDeviceMac(tv, "AA:BB:CC:DD:EE:10", andPowerOn = false)
            model.closeMacConfigDialog()
            model.openMacConfigDialog(tv)
        } finally {
            release.complete(Unit)
            transaction.join()
        }
        awaitQuickRemoteJobs(model.viewModelScope.coroutineContext[Job]!!.children.filter { it !in initialJobs }.toList())
        assertEquals("AA:BB:CC:DD:EE:10", db.tvDeviceDao().getDeviceById(tv.id)!!.macAddress)
        assertTrue("The new dialog belongs to a new user interaction", model.showMacConfigDialog.value)
    }
}

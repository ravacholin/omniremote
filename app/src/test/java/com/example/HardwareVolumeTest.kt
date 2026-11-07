package com.example

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.controller.UnifiedTvController
import com.example.data.local.AppDatabase
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.data.model.TvDeviceEntity
import com.example.ui.MainViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HardwareVolumeTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() = resetDatabase()
        override fun after() = resetDatabase()
    }).around(compose)
    private lateinit var activity: MainActivity
    private lateinit var model: MainViewModel
    private lateinit var controller: UnifiedTvController

    @Before fun setup() {
        activity = compose.activity
        // These tests exercise native key dispatch; the separate dialog test renders UI.
        compose.runOnUiThread { activity.setContent { } }
        model = ViewModelProvider(activity)[MainViewModel::class.java]
        controller = field(model, "controller")
    }

    @After fun teardown() {
        controller.disconnectCurrent()
        field<kotlinx.coroutines.CoroutineScope>(controller, "scope").cancel()
        model.viewModelScope.cancel()
    }

    @Test fun `physical volume controls the selected Android TV Samsung TV and Mac`() {
        for (type in DeviceType.entries) {
            connect(type)
            for ((code, key) in listOf(
                KeyEvent.KEYCODE_VOLUME_UP to RemoteKey.VOL_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN to RemoteKey.VOL_DOWN
            )) {
                assertTrue("$type must consume volume down", dispatch(KeyEvent.ACTION_DOWN, code))
                assertEquals(key, model.lastDispatchedKey.value)
                clearLastKey()
                assertTrue("$type must consume volume up", dispatch(KeyEvent.ACTION_UP, code))
                assertNull("Releasing must not send another command", model.lastDispatchedKey.value)
            }
        }
    }

    @Test fun `holding a volume button repeats remote commands`() {
        connect()
        assertTrue(dispatch(KeyEvent.ACTION_DOWN))
        clearLastKey()
        assertTrue(dispatch(KeyEvent.ACTION_DOWN, repeat = 1))
        assertEquals(RemoteKey.VOL_UP, model.lastDispatchedKey.value)
        clearLastKey()
        assertTrue(dispatch(KeyEvent.ACTION_DOWN, repeat = 2))
        assertEquals(RemoteKey.VOL_UP, model.lastDispatchedKey.value)
        assertTrue(dispatch(KeyEvent.ACTION_UP))
    }


    @Test fun `unavailable connections keep volume local`() {
        connect()
        for (state in listOf(
            ConnectionState.Disconnected,
            ConnectionState.Connecting("TV", "127.0.0.1"),
            ConnectionState.WaitingAuth("TV", "Authorize"),
            ConnectionState.Error("Offline")
        )) {
            connection.value = state
            dispatch(KeyEvent.ACTION_DOWN)
            dispatch(KeyEvent.ACTION_UP)
            assertNull("$state must not send remote keys", model.lastDispatchedKey.value)
        }
    }

    @Test fun `paused app does not control remote volume`() {
        connect()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        dispatch(KeyEvent.ACTION_DOWN)
        dispatch(KeyEvent.ACTION_UP)
        assertNull(model.lastDispatchedKey.value)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }

    @Test fun `without a selected device volume stays local`() {
        connect()
        field<MutableStateFlow<TvDeviceEntity?>>(model, "_activeDevice").value = null
        dispatch(KeyEvent.ACTION_DOWN)
        dispatch(KeyEvent.ACTION_UP)
        assertNull(model.lastDispatchedKey.value)
    }

    @Test fun `other physical keys are not mapped to remote commands`() {
        connect()
        dispatch(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
        dispatch(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)
        assertNull(model.lastDispatchedKey.value)
    }

    @Test fun `connection loss during a press consumes release without sending more commands`() {
        connect()
        assertTrue(dispatch(KeyEvent.ACTION_DOWN))
        clearLastKey()
        connection.value = ConnectionState.Disconnected
        assertTrue(dispatch(KeyEvent.ACTION_DOWN, repeat = 1))
        assertTrue(dispatch(KeyEvent.ACTION_UP))
        assertNull(model.lastDispatchedKey.value)
    }

    @Test fun `connecting during a local press waits for a new press`() {
        connect()
        connection.value = ConnectionState.Disconnected
        dispatch(KeyEvent.ACTION_DOWN)
        connection.value = ConnectionState.Connected("TV", "127.0.0.1")
        dispatch(KeyEvent.ACTION_DOWN, repeat = 1)
        dispatch(KeyEvent.ACTION_UP)
        assertNull(model.lastDispatchedKey.value)
        assertTrue(dispatch(KeyEvent.ACTION_DOWN))
        assertEquals(RemoteKey.VOL_UP, model.lastDispatchedKey.value)
        dispatch(KeyEvent.ACTION_UP)
    }

    private val connection: MutableStateFlow<ConnectionState>
        get() = field(controller, "_connectionState")

    private fun connect(type: DeviceType = DeviceType.SAMSUNG_TIZEN) {
        val device = TvDeviceEntity(id = 1, name = "Remote", deviceType = type, ipAddress = "127.0.0.1")
        field<MutableStateFlow<TvDeviceEntity?>>(model, "_activeDevice").value = device
        controller.javaClass.getDeclaredField("activeDevice").apply {
            isAccessible = true
            set(controller, device)
        }
        connection.value = ConnectionState.Connected(device.name, device.ipAddress)
        clearLastKey()
    }

    private fun clearLastKey() {
        field<MutableStateFlow<RemoteKey?>>(controller, "_lastDispatchedKey").value = null
    }

    private fun dispatch(action: Int, code: Int = KeyEvent.KEYCODE_VOLUME_UP, repeat: Int = 0): Boolean =
        activity.dispatchKeyEvent(KeyEvent(0L, 0L, action, code, repeat))

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(owner) as T
    }

    private fun resetDatabase() {
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            (get(null) as? AppDatabase)?.close()
            set(null, null)
        }
    }
}

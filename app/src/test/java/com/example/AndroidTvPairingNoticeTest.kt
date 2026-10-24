package com.example

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.controller.UnifiedTvController
import com.example.data.local.AppDatabase
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.ui.MainScreen
import com.example.ui.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidTvPairingNoticeTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private lateinit var model: MainViewModel
    private val tv = TvDeviceEntity(
        id = 1, name = "FlowBox Z3", deviceType = DeviceType.ANDROID_TV,
        ipAddress = "127.0.0.1", port = 6466
    )

    @Before fun setup() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        resetDatabase()
        application.deleteDatabase("omni_remote_database.db")
        model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[MainViewModel::class.java]
        // Simulate selection before the client's non-observable pairing flag is restored.
        @Suppress("UNCHECKED_CAST")
        val activeDevice = MainViewModel::class.java.getDeclaredField("_activeDevice").let {
            it.isAccessible = true
            it.get(model) as MutableStateFlow<TvDeviceEntity?>
        }
        activeDevice.value = tv
    }

    @After fun teardown() {
        val job = model.viewModelScope.coroutineContext[Job]
        store.clear()
        runBlocking {
            withTimeout(5000) {
                while (job?.isCompleted == false) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
        }
        resetDatabase()
    }

    @Test fun `connected TV never asks for PIN even with a stale pairing flag`() {
        setConnection(ConnectionState.Connected(tv.name, tv.ipAddress))
        compose.setContent { MainScreen(model) }
        compose.onNodeWithText("Con el código PIN que aparece en pantalla").assertDoesNotExist()
        compose.onNodeWithText("VINCULAR GOOGLE TV").assertDoesNotExist()
    }

    @Test fun `reconnecting and disconnected TV do not infer that a PIN is required`() {
        setConnection(ConnectionState.Connecting(tv.name, tv.ipAddress))
        compose.setContent { MainScreen(model) }
        compose.onNodeWithText("Con el código PIN que aparece en pantalla").assertDoesNotExist()
        compose.runOnIdle { setConnection(ConnectionState.Disconnected) }
        compose.onNodeWithText("Con el código PIN que aparece en pantalla").assertDoesNotExist()
    }

    @Test fun `explicit authorization request still offers PIN pairing and clears on connection`() {
        setConnection(ConnectionState.WaitingAuth(tv.name, "La TV requiere vinculación"))
        compose.setContent { MainScreen(model) }
        compose.onNodeWithText("PIN").assertExists()
        compose.runOnIdle { setConnection(ConnectionState.Connected(tv.name, tv.ipAddress)) }
        compose.onNodeWithText("PIN").assertDoesNotExist()
        compose.onNodeWithText("Con el código PIN que aparece en pantalla").assertDoesNotExist()
    }

    private fun setConnection(state: ConnectionState) {
        val controller = MainViewModel::class.java.getDeclaredField("controller").let {
            it.isAccessible = true
            it.get(model) as UnifiedTvController
        }
        @Suppress("UNCHECKED_CAST")
        val flow = UnifiedTvController::class.java.getDeclaredField("_connectionState").let {
            it.isAccessible = true
            it.get(controller) as MutableStateFlow<ConnectionState>
        }
        flow.value = state
    }

    private fun resetDatabase() {
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            (get(null) as? AppDatabase)?.close()
            set(null, null)
        }
    }
}

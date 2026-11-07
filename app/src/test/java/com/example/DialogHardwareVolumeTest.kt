package com.example

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.controller.UnifiedTvController
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.data.model.TvDeviceEntity
import com.example.ui.MainViewModel
import com.example.ui.components.AddTvAppDialog
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DialogHardwareVolumeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]
    private val controller: UnifiedTvController get() = field(model, "controller")

    @After fun teardown() {
        controller.disconnectCurrent()
        field<kotlinx.coroutines.CoroutineScope>(controller, "scope").cancel()
        model.viewModelScope.cancel()
    }

    @Test fun `volume controls the remote while an app dialog has focus`() {
        compose.runOnUiThread {
            val device = TvDeviceEntity(id = 1, name = "Remote", deviceType = DeviceType.SAMSUNG_TIZEN,
                ipAddress = "127.0.0.1")
            field<MutableStateFlow<TvDeviceEntity?>>(model, "_activeDevice").value = device
            controller.javaClass.getDeclaredField("activeDevice").apply {
                isAccessible = true
                set(controller, device)
            }
            field<MutableStateFlow<ConnectionState>>(controller, "_connectionState").value =
                ConnectionState.Connected(device.name, device.ipAddress)
            // The Compose rule owns the frame clock and cleanup for this real window.
            compose.activity.setContent {
                MyApplicationTheme {
                    AddTvAppDialog(
                        isOpen = true,
                        deviceType = DeviceType.SAMSUNG_TIZEN,
                        onDismiss = {},
                        onConfirm = { _, _, _, _, _, _ -> }
                    )
                }
            }
        }
        compose.runOnIdle {
            val window = ShadowDialog.getLatestDialog().window!!
            assertTrue(window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
            assertEquals(RemoteKey.VOL_DOWN, model.lastDispatchedKey.value)
            field<MutableStateFlow<RemoteKey?>>(controller, "_lastDispatchedKey").value = null
            assertTrue(window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN)))
            assertNull(model.lastDispatchedKey.value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(owner) as T
    }
}

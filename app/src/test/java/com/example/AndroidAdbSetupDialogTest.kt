package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.example.controller.AdbSetupState
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.ui.components.AndroidAdbSetupDialog
import com.example.ui.components.TvDeviceCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidAdbSetupDialogTest {
    @get:Rule val compose = createComposeRule()
    private val state = AdbSetupState(TvDeviceEntity(id = 7, name = "FlowBox", deviceType = DeviceType.ANDROID_TV,
        ipAddress = "192.168.1.50"), "com.tribalapps.tvdimmer")

    @Test fun `setup validates port and submits the chosen TCP endpoint`() {
        var submitted: String? = null
        compose.setContent { AndroidAdbSetupDialog(state, { submitted = it }, {}, {}) }
        compose.onNodeWithTag("adb_port").performTextReplacement("0")
        compose.onNodeWithTag("adb_connect").assertIsNotEnabled()
        compose.onNodeWithTag("adb_port").performTextReplacement("65536")
        compose.onNodeWithTag("adb_connect").assertIsNotEnabled()
        compose.onNodeWithTag("adb_port").performTextReplacement("5656")
        compose.onNodeWithTag("adb_connect").performClick()
        assertEquals("5656", submitted)
    }

    @Test fun `authorization wait prevents resubmit but leaves cancellation available`() {
        var cancelled = false
        compose.setContent { AndroidAdbSetupDialog(state.copy(busy = true), { fail("Already connecting") }, { cancelled = true }, {}) }
        compose.onNodeWithTag("adb_connect").assertIsNotEnabled()
        compose.onNodeWithTag("adb_port").assertIsNotEnabled()
        compose.onNodeWithText("Cancelar").performClick()
        assertTrue(cancelled)
    }

    @Test fun `Android TV card keeps ADB and delete actions clickable on a narrow screen`() {
        var deleted = false
        var configured = false
        var paired = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
              Box(Modifier.width(280.dp)) {
                TvDeviceCard(state.device, false, {}, {}, { deleted = true }, {},
                    onPair = { paired = true }, onConfigureAdb = { configured = true })
              }
            }
        }
        compose.onNodeWithTag("pair_device_7").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithTag("adb_device_7").assertIsDisplayed().performTouchInput { click() }
        val delete = compose.onNodeWithTag("delete_device_7").assertIsDisplayed()
        val bounds = delete.getUnclippedBoundsInRoot()
        assertTrue("Delete must retain usable width", bounds.right - bounds.left >= 24.dp)
        delete.performTouchInput { click() }
        assertTrue(configured)
        assertTrue(deleted)
        assertTrue(paired)
    }
}

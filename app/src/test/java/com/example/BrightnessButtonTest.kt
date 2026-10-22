package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.ui.components.RemoteDpad
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BrightnessButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `brightness is the fourth navigation action on Android TV`() {
        var launches = 0
        val keys = mutableListOf<RemoteKey>()
        compose.setContent {
            RemoteDpad(
                deviceType = DeviceType.ANDROID_TV,
                onKeyPress = { keys.add(it) },
                onBrightnessClick = { launches++ }
            )
        }
        compose.onNodeWithContentDescription("Back").assertExists()
        compose.onNodeWithContentDescription("Home").assertExists()
        compose.onNodeWithContentDescription("Menu").assertExists()
        compose.onNodeWithContentDescription("Brightness").assertExists()
        compose.onNodeWithContentDescription("Source").assertDoesNotExist()
        compose.onNodeWithContentDescription("Brightness").performClick()
        assertEquals(1, launches)
        assertTrue(keys.isEmpty())
    }

    @Test
    fun `Samsung uses the fourth button for Bluetooth audio instead of TvDimmer`() {
        val keys = mutableListOf<RemoteKey>()
        var launches = 0
        compose.setContent {
            RemoteDpad(
                deviceType = DeviceType.SAMSUNG_TIZEN,
                onKeyPress = { keys.add(it) },
                onBrightnessClick = { launches++ }
            )
        }
        compose.onNodeWithContentDescription("Bluetooth").performClick()
        assertTrue(keys.isEmpty())
        assertEquals(1, launches)
    }
}

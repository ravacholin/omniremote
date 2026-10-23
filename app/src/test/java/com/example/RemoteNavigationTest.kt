package com.example

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.example.data.model.ConnectionState
import com.example.ui.RemoteMode
import com.example.ui.RemoteModeTabButton
import com.example.ui.components.DeviceSelectorBar
import com.example.ui.components.RemoteIcons
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RemoteNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun materialSurfacesStayMonochromeIncludingMenusAndDialogs() {
        var colors = emptyList<Color>()
        compose.setContent {
            MyApplicationTheme {
                val scheme = MaterialTheme.colorScheme
                colors = listOf(
                    scheme.primary, scheme.secondary, scheme.tertiary, scheme.error,
                    scheme.surface, scheme.surfaceVariant, scheme.surfaceBright, scheme.surfaceDim,
                    scheme.surfaceContainerLowest, scheme.surfaceContainerLow, scheme.surfaceContainer,
                    scheme.surfaceContainerHigh, scheme.surfaceContainerHighest,
                    scheme.inverseSurface, scheme.inverseOnSurface, scheme.inversePrimary
                )
            }
        }
        compose.runOnIdle {
            colors.forEach { color ->
                assertEquals("Red and green differ in $color", color.red, color.green, 0.001f)
                assertEquals("Green and blue differ in $color", color.green, color.blue, 0.001f)
            }
        }
    }

    @Test fun modesHaveVisibleLabelsAndKeepTheirActionsOnSmallPhones() {
        val selected = mutableListOf<RemoteMode>()
        compose.setContent {
            MyApplicationTheme {
                Row(Modifier.width(288.dp)) {
                    RemoteMode.entries.forEach { mode ->
                        RemoteModeTabButton(
                            mode, RemoteIcons.Dpad, mode == RemoteMode.DPAD, Color.White,
                            onClick = { selected.add(mode) }, modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Control").assertIsDisplayed()
        compose.onNodeWithText("Apps").assertIsDisplayed().performClick()
        compose.onNodeWithText("Teclado").assertIsDisplayed().performClick()
        compose.onNodeWithTag("tab_dpad").assertIsSelected()
        assertEquals(listOf(RemoteMode.APPS, RemoteMode.NUMPAD), selected)
    }

    @Test fun headerActionsCanBeIdentifiedWithoutRecognizingIcons() {
        var scans = 0
        var settings = 0
        compose.setContent {
            MyApplicationTheme {
                DeviceSelectorBar(
                    activeDevice = null, allDevices = emptyList(),
                    connectionState = ConnectionState.Disconnected,
                    onSelectDevice = {}, onOpenManager = { settings++ },
                    onOpenScan = { scans++ }, onPowerClick = {},
                    modifier = Modifier.width(288.dp)
                )
            }
        }
        compose.onNodeWithText("Buscar TV").assertIsDisplayed().performClick()
        compose.onNodeWithText("Ajustes").assertIsDisplayed().performClick()
        compose.onNodeWithText("Vibración sí").assertIsDisplayed()
        assertEquals(1, scans)
        assertEquals(1, settings)
    }
}

package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.example.ui.RemoteMode
import com.example.ui.RemoteModeTabs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RemoteModeTabsSwipeTest {
    @get:Rule val compose = createComposeRule()
    private val selected = mutableListOf<RemoteMode>()

    private fun showTabs() {
        compose.setContent {
            var tab by remember { mutableStateOf(RemoteMode.DPAD) }
            Box(Modifier.width(360.dp)) {
                RemoteModeTabs(activeTab = tab, onSelect = { tab = it; selected.add(it) })
            }
        }
    }

    @Test fun swipingAcrossTheRowSelectsEachTabUnderTheFinger() {
        showTabs()
        compose.onNodeWithTag("remote_mode_tabs").performTouchInput {
            down(centerLeft + Offset(10f, 0f))
            moveTo(center, delayMillis = 100)
            moveTo(centerRight - Offset(10f, 0f), delayMillis = 100)
            up()
        }
        compose.runOnIdle { assertEquals(listOf(RemoteMode.APPS, RemoteMode.NUMPAD), selected.toList()) }
        selected.clear()
        compose.onNodeWithTag("remote_mode_tabs").performTouchInput {
            down(centerRight - Offset(10f, 0f))
            moveTo(centerLeft + Offset(10f, 0f), delayMillis = 150)
            up()
        }
        compose.runOnIdle { assertEquals(RemoteMode.DPAD, selected.last()) }
    }

    @Test fun tappingATabStillSelectsIt() {
        showTabs()
        compose.onNodeWithTag("tab_apps").performClick()
        compose.runOnIdle { assertEquals(listOf(RemoteMode.APPS), selected.toList()) }
    }
}

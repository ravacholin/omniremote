package com.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.model.DeviceType
import com.example.data.model.MacLinkEntity
import com.example.data.model.TvDeviceEntity
import com.example.share.SharedMacLink
import com.example.ui.components.MacShareDialog
import com.example.ui.components.MacQueueDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MacShareUiTest {
    @get:Rule val compose = createComposeRule()
    private val mac = TvDeviceEntity(id = 7, name = "Mac salón", deviceType = DeviceType.MACOS, ipAddress = "192.168.1.7")
    private val other = mac.copy(id = 8, name = "Mac estudio")

    @Test fun `offline share saves explicitly chosen Mac and opening needs its connection`() {
        var saved: Long? = null
        var connected by mutableStateOf<Long?>(null)
        var opened: Long? = null
        compose.setContent {
            MacShareDialog(requestId = "one", link = SharedMacLink.parse("https://example.com"),
                macs = listOf(mac, other), activeMacId = connected, connected = connected != null,
                busy = false, error = null, onSave = { saved = it }, onOpen = { opened = it },
                onConnect = { connected = it }, onSetup = {}, onDismiss = {})
        }
        compose.onNodeWithText("Abrir").assertIsNotEnabled()
        compose.onNodeWithText("Mac estudio").performClick()
        compose.onNodeWithText("Guardar para después").performClick()
        assertEquals(8L, saved)
        assertNull(opened)
        compose.onNodeWithText("Conectar").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(8L, connected) }
        compose.onNodeWithText("Abrir").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(8L, opened) }
        compose.onNodeWithText("Mac salón").performScrollTo().performClick()
        compose.onNodeWithText("Abrir").assertIsNotEnabled()
    }

    @Test fun `invalid share offers explanation and cannot save or open`() {
        compose.setContent {
            MacShareDialog("invalid", null, listOf(mac), mac.id, true, false, null,
                onSave = { fail("Invalid share saved") }, onOpen = { fail("Invalid share opened") },
                onConnect = {}, onSetup = {}, onDismiss = {})
        }
        compose.onNodeWithTag("mac_share_invalid").assertExists()
        compose.onNodeWithText("Guardar para después").assertIsNotEnabled()
        compose.onNodeWithText("Abrir").assertIsNotEnabled()
    }

    @Test fun `busy share cannot be submitted twice`() {
        compose.setContent {
            MacShareDialog("busy", SharedMacLink.parse("https://example.com"), listOf(mac), mac.id, true, true, null,
                onSave = { fail("Double save") }, onOpen = { fail("Double open") }, onConnect = {}, onSetup = {}, onDismiss = {})
        }
        compose.onNodeWithText("Guardar para después").assertIsNotEnabled()
        compose.onNodeWithText("Abrir").assertIsNotEnabled()
    }

    @Test fun `queue opens only next and exposes removal while offline`() {
        var online by mutableStateOf(false)
        var next = 0
        var removed: Long? = null
        compose.setContent {
            MacQueueDialog("Mac salón", listOf(MacLinkEntity(11, 7, "https://example.com/1", "Primero"),
                MacLinkEntity(12, 7, "https://example.com/2", "Segundo")), online, false, null,
                onNext = { next++ }, onRemove = { removed = it }, onDismiss = {})
        }
        compose.onNodeWithText("Abrir siguiente").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Eliminar Segundo").performClick()
        assertEquals(12L, removed)
        compose.runOnIdle { online = true }
        compose.onNodeWithText("Abrir siguiente").performClick()
        assertEquals(1, next)
    }
}

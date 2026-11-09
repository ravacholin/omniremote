package com.example

import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.controller.MacMediaState
import com.example.network.*
import com.example.ui.components.MacRemotePanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class MacMediaSelectorTest {
    @get:Rule val compose=createComposeRule()
    private val first=MacMediaSource("chrome:42:11:YOUTUBE",MacMediaService.YOUTUBE,"42",tabId="11",windowId="1",title="Same title",
        url="https://www.youtube.com/watch?v=a",verified=true,playback=MacPlayback.PAUSED,capabilities=listOf("PLAY_PAUSE"))
    @Test fun `picker distinguishes same titled tabs and selection does not send playback`() {
        var selected:String?=null;var keys=0;var refreshes=0
        compose.setContent { MacRemotePanel(volume=null,onKeyPress={keys++},onVolumeChange={},
            media=MacMediaState(sources=listOf(first,first.copy(id="chrome:42:12:YOUTUBE",tabId="12",windowId="2"))),
            onSelectMedia={selected=it},onRefreshMedia={refreshes++}) }
        compose.onNodeWithTag("mac_media_selector").performClick()
        compose.onNodeWithText("Automático").assertExists()
        compose.onNodeWithText("Same title · ventana 1 · pestaña 11").assertExists()
        compose.onNodeWithText("Same title · ventana 2 · pestaña 12").assertExists()
        compose.onNodeWithTag("mac_source_chrome:42:12:YOUTUBE").performClick()
        assertEquals("chrome:42:12:YOUTUBE",selected);assertEquals(0,keys);assertEquals(1,refreshes)
    }
    @Test fun `unsupported skips are disabled while paused play remains available`() {
        compose.setContent { MacRemotePanel(volume=null,onKeyPress={},onVolumeChange={},
            media=MacMediaState(sources=listOf(first),selectedId=first.id)) }
        compose.onNodeWithTag("mac_previous").assertIsNotEnabled()
        compose.onNodeWithTag("mac_next").assertIsNotEnabled()
        compose.onNodeWithTag("mac_play_pause").assertIsEnabled()
        compose.onNodeWithText("Última consulta: Pausado").assertExists()
    }
    @Test fun `permission failure blocks media but keeps system volume independent`() {
        val blocked=first.copy(verified=false,issue=MacMediaIssue("CHROME_JAVASCRIPT","Habilitá JavaScript en Chrome"))
        compose.setContent { MacRemotePanel(volume=MacVolume(50,false),onKeyPress={},onVolumeChange={},
            media=MacMediaState(sources=listOf(blocked),selectedId=blocked.id)) }
        compose.onNodeWithTag("mac_play_pause").assertIsNotEnabled()
        compose.onNodeWithTag("mac_vol_up").assertIsEnabled()
        compose.onNodeWithText("Habilitá JavaScript en Chrome").assertExists()
    }
    @Test fun `controls remain reachable in a short bounded panel`() {
        compose.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.height(280.dp)) {
                MacRemotePanel(volume=MacVolume(50,false),onKeyPress={},onVolumeChange={},
                    media=MacMediaState(sources=listOf(first),selectedId=first.id))
            }
        }
        compose.onNodeWithTag("mac_play_pause").performScrollTo().assertIsDisplayed()
    }

    @Test fun `show button invokes its own action without sending playback`() {
        var reveals=0; var keys=0; var selections=0
        compose.setContent { MacRemotePanel(volume=null,onKeyPress={keys++},onVolumeChange={},
            media=MacMediaState(sources=listOf(first),selectedId=first.id),
            onRevealMedia={reveals++},onSelectMedia={selections++}) }
        compose.onNodeWithText("Mostrame esto").performClick()
        assertEquals(1,reveals); assertEquals(0,keys); assertEquals(0,selections)
    }

    @Test fun `show button blocks automatic disconnected busy loading and missing sources`() {
        val state=androidx.compose.runtime.mutableStateOf(MacMediaState())
        val connected=androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { MacRemotePanel(volume=null,onKeyPress={},onVolumeChange={},
            media=state.value,mediaConnected=connected.value) }
        compose.onNodeWithTag("mac_show_source").assertIsNotEnabled()
        compose.onNodeWithText("Elegí una fuente para mostrarla en la Mac.").assertExists()
        for (s in listOf(
            MacMediaState(sources=listOf(first),selectedId=first.id,busy=true),
            MacMediaState(sources=listOf(first),selectedId=first.id,loading=true),
            MacMediaState(sources=listOf(first.copy(verified=false,issue=MacMediaIssue("SOURCE_GONE","Cerrada"))),selectedId=first.id),
            MacMediaState(selectedId=first.id))) {
            compose.runOnIdle { state.value=s }
            compose.onNodeWithTag("mac_show_source").assertIsNotEnabled()
        }
        compose.runOnIdle { state.value=MacMediaState(sources=listOf(first),selectedId=first.id); connected.value=false }
        compose.onNodeWithTag("mac_show_source").assertIsNotEnabled()
        compose.runOnIdle { connected.value=true }
        compose.onNodeWithTag("mac_show_source").assertIsEnabled()
    }

    @Test fun `show is available even when Chrome JavaScript blocks playback`() {
        val blocked=first.copy(verified=false,issue=MacMediaIssue("CHROME_JAVASCRIPT","Habilitá JavaScript"))
        compose.setContent { MacRemotePanel(volume=null,onKeyPress={},onVolumeChange={},
            media=MacMediaState(sources=listOf(blocked),selectedId=first.id)) }
        compose.onNodeWithTag("mac_show_source").assertIsEnabled()
        compose.onNodeWithTag("mac_play_pause").assertIsNotEnabled()
    }
}

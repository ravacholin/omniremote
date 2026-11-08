package com.example

import com.example.controller.MacMediaController
import com.example.data.model.RemoteKey
import com.example.network.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MacMediaControllerTest {
    private val candidate = """{"id":"app:com.spotify.client:42","service":"SPOTIFY","processId":"42"}"""
    private val live = """{"id":"app:com.spotify.client:42","service":"SPOTIFY","processId":"42","title":"Track","token":"one","verified":true,"playback":"PAUSED","capabilities":["PLAY","PAUSE","PLAY_PAUSE","NEXT"]}"""
    private val catalog get() = MacResult.Ok("""{"sources":[$candidate]}""")
    private val inspect get() = MacResult.Ok("""{"source":$live}""")

    @Test fun `manual source stays selected after catalog loses it and never falls back`() = runTest {
        val replies = ArrayDeque(listOf(catalog, inspect, MacResult.Ok("""{"sources":[]}""")))
        var calls = 0
        val c=MacMediaController(this, { _,_ -> calls++; replies.removeFirst() })
        c.refresh(); advanceUntilIdle()
        c.select("app:com.spotify.client:42")
        c.refresh(); advanceUntilIdle()
        assertEquals("app:com.spotify.client:42",c.state.value.selectedId)
        assertFalse(c.state.value.canSend(RemoteKey.PLAY_PAUSE))
        assertTrue(c.send(RemoteKey.PLAY_PAUSE)); advanceUntilIdle()
        assertEquals(3,calls)
    }

    @Test fun `changing selection invalidates a queued action and its response`() = runTest {
        val gate=CompletableDeferred<MacResult>(); var calls=0; var guard:(()->Boolean)?=null
        val c=MacMediaController(this, { _,canSend ->
            calls++; if(calls==1) catalog else if(calls==2) inspect else { guard=canSend; gate.await() }
        })
        c.refresh(); advanceUntilIdle(); c.select("app:com.spotify.client:42")
        assertTrue(c.send(RemoteKey.PLAY_PAUSE)); runCurrent()
        c.select(null)
        assertFalse(guard!!.invoke())
        gate.complete(inspect); advanceUntilIdle()
        assertNull(c.state.value.selectedId)
        assertEquals(3,calls)
    }

    @Test fun `older discovery cannot overwrite a reset session`() = runTest {
        val gate=CompletableDeferred<MacResult>()
        val c=MacMediaController(this,{_,_->gate.await()})
        c.refresh(); runCurrent(); c.reset(); gate.complete(catalog); advanceUntilIdle()
        assertTrue(c.state.value.sources.isEmpty()); assertFalse(c.state.value.loading)
    }

    @Test fun `partial permission errors retain healthy sources and show configuration issue`() = runTest {
        val nativeAndWeb="""{"sources":[$candidate,{"id":"chrome:7:9:YOUTUBE","service":"YOUTUBE","processId":"7","tabId":"9","url":"https://www.youtube.com/watch?v=a"}]}"""
        val replies=ArrayDeque(listOf(MacResult.Ok(nativeAndWeb),inspect,
            MacResult.Ok("""{"error":{"code":"CHROME_JAVASCRIPT","message":"Enable JavaScript"}}""")))
        val c=MacMediaController(this,{_,_->replies.removeFirst()})
        c.refresh(); advanceUntilIdle()
        assertEquals(2,c.state.value.sources.size)
        assertTrue(c.state.value.sources.first().verified)
        assertFalse(c.state.value.sources.last().verified)
        assertEquals("CHROME_JAVASCRIPT",c.state.value.sources.last().issue?.code)
    }

    @Test fun `changed content refreshes the pinned source but does not apply an old tap`() = runTest {
        val changed=MacResult.Ok("""{"source":${live.replace("\"one\"","\"two\"")}}""")
        val replies=ArrayDeque(listOf(catalog,inspect,changed));var calls=0
        val c=MacMediaController(this,{_,_->calls++;replies.removeFirst()})
        c.refresh(); advanceUntilIdle(); c.select("app:com.spotify.client:42")
        c.send(RemoteKey.PLAY_PAUSE); advanceUntilIdle()
        assertEquals(3,calls)
        assertEquals("two",c.state.value.selectedSource?.token)
        assertEquals("app:com.spotify.client:42",c.state.value.selectedId)
    }
    @Test fun `restarted Chrome clears its ephemeral selection without restoring by title`() = runTest {
        val web="""{"id":"chrome:7:9:YOUTUBE","service":"YOUTUBE","processId":"7","tabId":"9","url":"https://www.youtube.com/watch?v=a","verified":true,"token":"one","capabilities":["PLAY_PAUSE"]}"""
        val replies=ArrayDeque(listOf(MacResult.Ok("""{"sources":[$web],"chromeProcessId":"7"}"""),
            MacResult.Ok("""{"source":$web}"""),
            MacResult.Ok("""{"error":{"code":"CHROME_RESTARTED","message":"Chrome restarted"}}""")))
        val c=MacMediaController(this,{_,_->replies.removeFirst()})
        c.refresh(); advanceUntilIdle(); c.select("chrome:7:9:YOUTUBE")
        c.invalidate(); c.refreshSelected(); advanceUntilIdle()
        assertNull(c.state.value.selectedId)
        assertFalse(c.state.value.busy)
    }

    @Test fun `same tab changing service cannot silently replace the pinned source`() = runTest {
        val youtube="""{"id":"chrome:7:9:YOUTUBE","service":"YOUTUBE","processId":"7","tabId":"9","url":"https://www.youtube.com/watch?v=a","verified":true,"token":"one","capabilities":["PLAY_PAUSE"]}"""
        val spotify="""{"id":"chrome:7:9:SPOTIFY_WEB","service":"SPOTIFY_WEB","processId":"7","tabId":"9","url":"https://open.spotify.com/track/a","verified":true,"token":"two","capabilities":["PLAY_PAUSE"]}"""
        val replies=ArrayDeque(listOf(MacResult.Ok("""{"sources":[$youtube],"chromeProcessId":"7"}"""),
            MacResult.Ok("""{"source":$youtube}"""),MacResult.Ok("""{"sources":[$spotify],"chromeProcessId":"7"}"""),
            MacResult.Ok("""{"source":$spotify}""")))
        val c=MacMediaController(this,{_,_->replies.removeFirst()})
        c.refresh(); advanceUntilIdle(); c.select("chrome:7:9:YOUTUBE")
        val oldRevision=c.state.value.revision
        c.refresh(); advanceUntilIdle()
        assertEquals(MacMediaService.YOUTUBE,c.state.value.selectedSource?.service)
        assertFalse(c.state.value.canSend(RemoteKey.PLAY_PAUSE))
        assertTrue(c.state.value.revision>oldRevision)
    }

}

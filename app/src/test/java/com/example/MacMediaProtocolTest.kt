package com.example

import com.example.network.*
import org.junit.Assert.*
import org.junit.Test

class MacMediaProtocolTest {
    @Test fun `only actual catalog hosts and supported routes are candidates`() {
        assertEquals(MacMediaService.YOUTUBE, MacMediaProtocol.serviceForUrl("https://www.youtube.com/watch?v=abc"))
        assertEquals(MacMediaService.YOUTUBE_MUSIC, MacMediaProtocol.serviceForUrl("https://music.youtube.com/watch?v=abc"))
        assertEquals(MacMediaService.SPOTIFY_WEB, MacMediaProtocol.serviceForUrl("https://open.spotify.com/intl-es/track/abc"))
        listOf("https://youtube.com.evil.test/watch?v=a", "https://evil.test/?youtube.com", "javascript:alert(1)", "https://www.youtube.com/results?search_query=a", "https://www.youtube.com/", "http://www.youtube.com/watch?v=a").forEach {
            assertNull(it, MacMediaProtocol.serviceForUrl(it))
        }
    }

    @Test fun `unverified candidates have no effective capabilities even if response claims them`() {
        val reply = MacMediaProtocol.parse("""{"sources":[{"id":"chrome:42:11:YOUTUBE","service":"YOUTUBE","title":"A \"title\"","url":"https://www.youtube.com/watch?v=a","tabId":"11","processId":"42","verified":false,"capabilities":["PLAY_PAUSE"]}]}""")
        val source = reply.sources.single()
        assertEquals("chrome:42:11:YOUTUBE", source.id)
        assertEquals("A \"title\"", source.title)
        assertFalse(source.supports(com.example.data.model.RemoteKey.PLAY_PAUSE))
    }

    @Test fun `malformed and unrelated sources cannot become actionable`() {
        assertNotNull(MacMediaProtocol.parse("not json").error)
        assertTrue(MacMediaProtocol.parse("""{"sources":[{"id":"chrome:42:11:YOUTUBE","service":"YOUTUBE","url":"https://evil.test/watch?v=a","tabId":"11","processId":"42","verified":true}]}""").sources.isEmpty())
    }
}

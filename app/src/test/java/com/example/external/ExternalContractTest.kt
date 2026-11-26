package com.example.external

import com.example.data.model.RemoteKey
import com.example.network.MacScripts
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExternalContractTest {
    @Test fun equivalentJsonHasSameFingerprintButTargetsDoNot() {
        val first = RemoteRequest.parse(1, 7, "SLEEP_AT", """{"hour":12,"minute":30}""")
        assertEquals(first.fingerprint(), RemoteRequest.parse(1, 7, "SLEEP_AT",
            """{"minute":30,"hour":12}""").fingerprint())
        assertNotEquals(first.fingerprint(), first.copy(deviceId = 8).fingerprint())
    }

    @Test fun rejectsCoercionAndUnknownParameters() {
        for (args in listOf("""{"level":"50"}""", """{"level":50.5}""",
            """{"level":101}""", """{"level":50,"retries":2}""",
            """{"level":50,"level":51}""", """{"level":50}{}""")) {
            assertThrows(IllegalArgumentException::class.java) {
                RemoteRequest.parse(1, 7, "SET_VOLUME", args)
            }
        }
    }

    @Test fun explicitMacPlaybackOmitsTheSystemToggleFallback() {
        for ((key, method) in listOf(RemoteKey.PLAY to "play", RemoteKey.PAUSE to "pause",
            RemoteKey.STOP to "pause")) {
            val script = checkNotNull(MacScripts.forExternalKey(key)).source
            assertTrue(script.contains("selected.$method();"))
            assertFalse(script.contains("mediaKey(16)"))
        }
        assertTrue(checkNotNull(MacScripts.forExternalKey(RemoteKey.PLAY_PAUSE)).source.contains("mediaKey(16)"))
    }

    @Test fun unknownStateIsOmittedFromOutput() {
        val reply = RemoteReply.verified(JSONObject().put("power", false)).encode("request-000001")
        val state = JSONObject(reply).getJSONObject("data")
        assertFalse(state.getBoolean("power"))
        assertFalse(state.has("playing"))
    }
}

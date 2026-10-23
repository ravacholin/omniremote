package com.example.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SamsungAppProtocolTest {
    @Test fun `Flow prefers current app over legacy Cablevision regardless of TV ordering`() {
        val legacy = SamsungInstalledApp("legacy-flow", "Cablevisión Flow", 2)
        val current = SamsungInstalledApp("current-flow", "Flow", 2)
        val personal = SamsungInstalledApp("personal-flow", "Personal Flow", 2)
        for (apps in listOf(listOf(legacy, personal, current), listOf(current, legacy, personal))) {
            val selected = SamsungAppProtocol.find(apps, "Flow")!!
            val payload = JSONObject(SamsungAppProtocol.launch(selected))
            assertEquals("current-flow", payload.getJSONObject("params").getJSONObject("data").getString("appId"))
        }
        assertEquals("personal-flow", SamsungAppProtocol.find(listOf(legacy, personal), "Flow")!!.id)
    }

    @Test fun `Flow resolves its real TV-specific ID and does not match Samsung Flow`() {
        val apps = SamsungAppProtocol.installedApps(JSONObject("""{"event":"ed.installedApp.get","data":{"data":[
          {"appId":"wrong","name":"Samsung Flow","app_type":1},
          {"appId":"tv-specific-flow","name":"Cablevisión Flow","app_type":2},
          {"appId":"regional-max","name":"HBO Max","app_type":2},
          {"appId":"native-apple","name":"Apple TV","app_type":1}
        ]}}"""))
        // Unicode in the provider name must not prevent discovery.
        val flow = SamsungAppProtocol.find(apps, "Flow")!!
        assertEquals("tv-specific-flow", flow.id)
        assertEquals("regional-max", SamsungAppProtocol.find(apps, "Max")!!.id)
        assertNull(SamsungAppProtocol.find(apps, "Paramount+"))
        val launch = JSONObject(SamsungAppProtocol.launch(flow))
        assertEquals("ms.channel.emit", launch.getString("method"))
        val params = launch.getJSONObject("params")
        assertEquals("ed.apps.launch", params.getString("event"))
        assertEquals("tv-specific-flow", params.getJSONObject("data").getString("appId"))
        assertEquals("DEEP_LINK", params.getJSONObject("data").getString("action_type"))
        assertEquals("NATIVE_LAUNCH", JSONObject(SamsungAppProtocol.launch(apps.last())).getJSONObject("params").getJSONObject("data").getString("action_type"))
    }
}

package com.example.network

import org.json.JSONObject
import java.util.Locale

internal data class SamsungInstalledApp(val id: String, val name: String, val type: Int)

internal object SamsungAppProtocol {
    fun installedApps(message: JSONObject): List<SamsungInstalledApp> {
        val data = message.optJSONObject("data") ?: return emptyList()
        val apps = data.optJSONArray("data") ?: return emptyList()
        return (0 until apps.length()).mapNotNull { index ->
            val app = apps.optJSONObject(index) ?: return@mapNotNull null
            val id = app.optString("appId")
            val name = app.optString("name")
            if (id.isBlank() || name.isBlank()) null else SamsungInstalledApp(id, name, app.optInt("app_type", 2))
        }
    }

    fun find(apps: List<SamsungInstalledApp>, name: String): SamsungInstalledApp? {
        fun normalize(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD).lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        // Samsung may return legacy and current Flow installations together.
        // Provider name priority must win over the order returned by the TV.
        if (normalize(name) in setOf("flow", "flowargentina")) {
            return listOf("flow", "personalflow", "flowtv", "cablevisionflow")
                .firstNotNullOfOrNull { alias -> apps.firstOrNull { normalize(it.name) == alias } }
        }
        val aliases = when (normalize(name)) {
            "max", "hbomax", "maxhbo" -> setOf("max", "hbomax")
            "paramount", "paramountplus" -> setOf("paramount", "paramountplus")
            "disney", "disneyplus" -> setOf("disney", "disneyplus")
            "appletv" -> setOf("appletv", "appletvplus")
            else -> setOf(normalize(name))
        }
        return apps.firstOrNull { normalize(it.name) in aliases }
    }

    fun requestApps(): String = JSONObject().put("method", "ms.channel.emit")
        .put("params", JSONObject().put("event", "ed.installedApp.get").put("to", "host")).toString()

    fun launch(app: SamsungInstalledApp): String = JSONObject().put("method", "ms.channel.emit")
        .put("params", JSONObject().put("event", "ed.apps.launch").put("to", "host")
            .put("data", JSONObject().put("appId", app.id)
                .put("action_type", if (app.type == 2) "DEEP_LINK" else "NATIVE_LAUNCH")
                .put("metaTag", ""))).toString()
}

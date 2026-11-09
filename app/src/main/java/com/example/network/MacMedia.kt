package com.example.network

import com.example.data.model.RemoteKey
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.net.URI

enum class MacMediaService(val label: String, val bundleId: String? = null) {
    SPOTIFY("Spotify · app", "com.spotify.client"), MUSIC("Música · app", "com.apple.Music"),
    YOUTUBE("YouTube · Chrome"), YOUTUBE_MUSIC("YouTube Music · Chrome"), SPOTIFY_WEB("Spotify · Chrome");
    val isWeb: Boolean get() = bundleId == null
}

enum class MacPlayback { PLAYING, PAUSED, UNKNOWN }

data class MacMediaIssue(val code: String, val message: String)

data class MacMediaSource(
    val id: String,
    val service: MacMediaService,
    val processId: String,
    val tabId: String = "",
    val windowId: String = "",
    val title: String = "",
    val url: String = "",
    val token: String = "",
    val playback: MacPlayback = MacPlayback.UNKNOWN,
    val capabilities: List<String> = emptyList(),
    val verified: Boolean = false,
    val observedAtMillis: Long = 0,
    val issue: MacMediaIssue? = null
) {
    val label: String get() = service.label
    val detail: String get() = if (service.isWeb) "${title.ifBlank { "Pestaña" }} · ventana $windowId · pestaña $tabId" else title
    val status: String get() = issue?.message ?: if (!verified) "Sin verificar" else when (playback) {
        MacPlayback.PLAYING -> "Reproduciendo"
        MacPlayback.PAUSED -> "Pausado"
        MacPlayback.UNKNOWN -> "Estado desconocido"
    }
    val observationLabel: String get() = if (!verified || issue != null) status else {
        val time = if (observedAtMillis > 0) " · " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
            .format(java.util.Date(observedAtMillis)) else ""
        "Última consulta: $status$time"
    }
    fun supports(key: RemoteKey): Boolean = verified && issue == null && MacMediaProtocol.action(key) in capabilities
}

data class MacMediaReply(
    val sources: List<MacMediaSource> = emptyList(),
    val source: MacMediaSource? = null,
    val issues: List<MacMediaIssue> = emptyList(),
    val error: MacMediaIssue? = null,
    val chromeProcessId: String? = null,
    val revealed: Boolean = false
)

object MacMediaProtocol {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(MacMediaReply::class.java)
    private val anyAdapter = moshi.adapter(Any::class.java)
    fun json(value: Any): String = anyAdapter.toJson(value)
    fun sourceJson(source: MacMediaSource): String = moshi.adapter(MacMediaSource::class.java).toJson(source)

    fun action(key: RemoteKey): String? = when (key) {
        RemoteKey.PLAY -> "PLAY"
        RemoteKey.PAUSE, RemoteKey.STOP -> "PAUSE"
        RemoteKey.PLAY_PAUSE -> "PLAY_PAUSE"
        RemoteKey.FAST_FORWARD -> "NEXT"
        RemoteKey.REWIND -> "PREVIOUS"
        else -> null
    }

    fun serviceForUrl(value: String): MacMediaService? = try {
        val u = URI(value)
        val p = u.path.orEmpty()
        if (u.scheme != "https" || u.userInfo != null) null else when (u.host) {
            "www.youtube.com", "youtube.com", "m.youtube.com" -> if (
                (p == "/watch" && u.rawQuery.orEmpty().split('&').any { it.startsWith("v=") && it.length > 2 }) ||
                Regex("^/(shorts|live)/[^/]+/?$").matches(p)
            ) MacMediaService.YOUTUBE else null
            "music.youtube.com" -> if (Regex("^/(watch|browse|playlist|search)?(/.*)?$").matches(p)) MacMediaService.YOUTUBE_MUSIC else null
            "open.spotify.com" -> if (p == "/" || Regex("^/(intl-[a-z-]+/)?(track|album|playlist|artist|collection|episode|show|search|genre|browse)(/.*)?$").matches(p)) MacMediaService.SPOTIFY_WEB else null
            else -> null
        }
    } catch (_: Exception) { null }

    fun parse(output: String): MacMediaReply = try {
        require(output.length <= 512 * 1024)
        val reply = requireNotNull(adapter.fromJson(output))
        reply.copy(sources = reply.sources.filter(::valid).distinctBy { it.id }.take(40), source = reply.source?.takeIf(::valid))
    } catch (_: Exception) {
        MacMediaReply(error = MacMediaIssue("INVALID_RESPONSE", "La Mac devolvió una respuesta multimedia no válida. Volvé a actualizar."))
    }

    private fun valid(s: MacMediaSource): Boolean {
        if (!s.processId.matches(Regex("[0-9]+"))) return false
        return if (s.service.isWeb) s.tabId.matches(Regex("[0-9]+")) &&
            s.id == "chrome:${s.processId}:${s.tabId}:${s.service.name}" && serviceForUrl(s.url) == s.service
        else s.id == "app:${s.service.bundleId}:${s.processId}"
    }
}

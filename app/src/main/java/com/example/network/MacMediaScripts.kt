package com.example.network

import com.example.data.model.RemoteKey

/** Fixed programs plus JSON data; tab titles/URLs never become executable code. */
object MacMediaScripts {
    private fun resource(name: String): String = requireNotNull(javaClass.getResourceAsStream("/$name")) {
        "Missing Mac media program: $name"
    }.bufferedReader().use { it.readText() }
    private val host by lazy { resource("mac_media_host.js") }
    private val browser by lazy { resource("mac_media_browser.js") }
    private fun script(op: String, source: MacMediaSource? = null, action: String? = null): MacScript {
        val sourceData = source?.let(MacMediaProtocol::sourceJson) ?: "null"
        return MacScript(MacScript.Language.JAVASCRIPT,
            "var REQUEST = {op:${MacMediaProtocol.json(op)},source:$sourceData,action:${action?.let(MacMediaProtocol::json) ?: "null"}};\n" +
                "var BROWSER_SOURCE = ${MacMediaProtocol.json(browser)};\n" + host)
    }
    val discover: MacScript get() = script("discover")
    fun inspect(source: MacMediaSource): MacScript = script("inspect", source)
    fun reveal(source: MacMediaSource): MacScript = script("reveal", source)
    fun command(source: MacMediaSource, key: RemoteKey): MacScript = script("action", source, requireNotNull(MacMediaProtocol.action(key)))
}

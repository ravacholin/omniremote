package com.example.share

import android.content.Intent
import android.net.Uri
import java.net.URI

/** Only web links are allowed across the remote execution boundary. */
class SharedMacLink private constructor(val url: String, val title: String) {
    companion object {
        fun fromIntent(intent: Intent): SharedMacLink? {
            if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return null
            return parse(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
                intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString())
        }

        fun parse(text: String, title: String? = null): SharedMacLink? {
            if (text.length > 32_768 || text.any { it == '\u0000' || (it.isISOControl() && it !in "\n\r\t") }) return null
            val input = text.trim()
            val candidate = if (input.startsWith("geo:", ignoreCase = true)) geoToMaps(input) ?: return null
                else {
                    val match = Regex("https?://[^\\s<>\"“”]+", RegexOption.IGNORE_CASE).find(input) ?: return null
                    if (match.range.first == 0 && match.value.length == input.length) match.value
                    else trimProsePunctuation(match.value)
                }
            val uri = try { URI(candidate) } catch (_: Exception) { return null }
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null || uri.port !in -1..65535 || candidate.length > 8192) return null
            val label = title?.trim()?.takeIf { it.isNotBlank() }?.take(200) ?: uri.host
            return SharedMacLink(candidate, label)
        }

        private fun trimProsePunctuation(value: String): String {
            var url = value
            do {
                val before = url
                url = url.trimEnd('.', ',', ';', '!')
                for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}')) {
                    while (url.endsWith(close) && url.count { it == close } > url.count { it == open }) url = url.dropLast(1)
                }
            } while (url != before)
            return url
        }

        private fun validCoordinates(value: String): Boolean {
            val parts = value.split(',')
            return parts.size == 2 && parts[0].toDoubleOrNull()?.let { it in -90.0..90.0 } == true &&
                parts[1].toDoubleOrNull()?.let { it in -180.0..180.0 } == true
        }

        private fun geoToMaps(input: String): String? {
            val coordinates = input.substringAfter(':').substringBefore('?')
            if (!validCoordinates(coordinates)) return null
            val query = Uri.parse("https://maps.invalid/?" + input.substringAfter('?', "")).getQueryParameter("q")
            val queriedPin = query?.let { Regex("^([-+0-9.]+,[-+0-9.]+)(?:\\((.*)\\))?$", RegexOption.DOT_MATCHES_ALL).matchEntire(it) }
            if (queriedPin != null && validCoordinates(queriedPin.groupValues[1])) {
                val label = queriedPin.groupValues[2].takeIf { it.isNotBlank() }
                return "https://maps.apple.com/?ll=${queriedPin.groupValues[1]}" + (label?.let { "&q=${Uri.encode(it)}" } ?: "")
            }
            val placeholder = coordinates.split(',').all { it.toDouble() == 0.0 }
            return if (placeholder && !query.isNullOrBlank()) "https://maps.apple.com/?q=${Uri.encode(query)}"
                else "https://maps.apple.com/?ll=$coordinates" + (query?.let { "&q=${Uri.encode(it)}" } ?: "")
        }
    }
}

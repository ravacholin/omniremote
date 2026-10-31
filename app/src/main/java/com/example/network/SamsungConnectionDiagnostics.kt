package com.example.network

import android.content.Context
import android.util.Base64
import com.example.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the actual authorization sequence across app restarts without retaining tokens. */
class SamsungConnectionDiagnostics(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("samsung_connection_diagnostics", Context.MODE_PRIVATE)
    private val network = TvLocalNetwork(context)

    fun record(ip: String, session: Long, event: String, token: String, detail: String = "") {
        // Diagnostics are best effort: they must never fail or delay authorization
        // on a synchronous disk write. SharedPreferences serializes apply() writes.
        runCatching { synchronized(lock) {
            val previous = runCatching { JSONArray(prefs.getString("events", "[]")) }.getOrDefault(JSONArray())
            val entries = JSONArray()
            for (index in maxOf(0, previous.length() - 79) until previous.length()) entries.put(previous.get(index))
            val fingerprint = if (token.isBlank()) "none" else "hmac-sha256:" +
                Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(fingerprintKey(), "HmacSHA256")) }
                    .doFinal(token.toByteArray(Charsets.UTF_8))
                    .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            entries.put(JSONObject().put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT).format(Date()))
                .put("tv", ip).put("session", session).put("event", event)
                .put("credential", fingerprint).put("detail", detail))
            prefs.edit().putString("events", entries.toString()).apply()
        } }
    }

    private fun fingerprintKey(): ByteArray {
        prefs.getString("fingerprint_key", null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("fingerprint_key", Base64.encodeToString(key, Base64.NO_WRAP)).apply()
        return key
    }

    fun recordStart(ip: String, session: Long, token: String, source: String) {
        val lanIp = runCatching { network.localIpv4() }.getOrNull().orEmpty()
        record(ip, session, "connect_start", token,
            "credential_source=$source; lan_ipv4=$lanIp; name=OmniRemote; WSS=8002")
    }

    fun report(): String = synchronized(lock) {
        "OmniRemote ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Samsung · diagnóstico 2026-10-31\n" +
            "Las credenciales se identifican por huellas HMAC locales; no se incluyen tokens ni la clave de las huellas.\n" +
            runCatching { JSONArray(prefs.getString("events", "[]")).toString(2) }.getOrDefault("[]")
    }

    private companion object { val lock = Any() }
}

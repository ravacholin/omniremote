package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.network.MacLogin
import com.example.network.MacLoginStore
import com.example.network.WakeOnLan

/**
 * Persistent token store for TV device authorization credentials.
 * Ensures Samsung Smart TV authorization tokens and Android TV pairing credentials
 * are permanently preserved across app restarts, re-scans, and device selections.
 */
class TvTokenStore(context: Context) : MacLoginStore {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun saveSamsungToken(ipAddress: String, token: String, deviceId: Long? = null, macAddress: String? = null) {
        if (token.isBlank()) return
        val cleanIp = ipAddress.trim()
        Log.d(TAG, "Persisting Samsung TV token for IP: $cleanIp (length: ${token.length})")

        // Every write gets a newer sequence number so lookups can tell which copy is current.
        val seq = prefs.getLong(KEY_SEQ, 0) + 1
        prefs.edit().apply {
            putLong(KEY_SEQ, seq)
            putToken(KEY_PREFIX_IP + cleanIp, token, seq)
            if (deviceId != null && deviceId > 0) {
                putToken(KEY_PREFIX_ID + deviceId, token, seq)
                putLong(KEY_IP_OWNER_ID + cleanIp, deviceId)
            }
            WakeOnLan.formatMacAddress(macAddress)?.let { mac ->
                putToken(KEY_PREFIX_MAC + mac, token, seq)
                putString(KEY_IP_OWNER_MAC + cleanIp, mac)
                if (deviceId != null && deviceId > 0) putString(KEY_ID_OWNER_MAC + deviceId, mac)
            }
            // Written synchronously: losing a fresh token means the TV asks for permission again.
            if (!commit()) Log.w(TAG, "Could not persist Samsung TV token for $cleanIp")
        }
    }

    private fun SharedPreferences.Editor.putToken(key: String, token: String, seq: Long) {
        putString(key, token)
        putLong(KEY_PREFIX_SEQ + key, seq)
    }

    /**
     * Returns the most recently saved token among the keys that belong to this TV.
     * Samsung TVs can issue a new token on reconnect, so an older copy stored under
     * another identity (IP, id or MAC) must never win over the latest one.
     */
    fun getSamsungToken(ipAddress: String, deviceId: Long? = null, macAddress: String? = null): String {
        val cleanIp = ipAddress.trim()
        val requestedMac = WakeOnLan.formatMacAddress(macAddress)
        val keys = mutableListOf<String>()
        // Stable hardware identity wins when DHCP assigns an old IP to another TV.
        if (requestedMac != null) {
            keys += KEY_PREFIX_MAC + requestedMac
            macAddress?.let { keys += KEY_PREFIX_MAC + it.trim().uppercase() }
        }
        val hasMacToken = keys.any { !prefs.getString(it, null).isNullOrBlank() }
        var conflict = false
        if (deviceId != null && deviceId > 0) {
            val owner = prefs.getString(KEY_ID_OWNER_MAC + deviceId, null)
            if (owner != null && requestedMac != null && owner != requestedMac) conflict = true
            else keys += KEY_PREFIX_ID + deviceId
        }
        if (!conflict) {
            val ownerMac = prefs.getString(KEY_IP_OWNER_MAC + cleanIp, null)
            val ownerId = prefs.getLong(KEY_IP_OWNER_ID + cleanIp, 0)
            val macMismatch = requestedMac != null && ownerMac != null && requestedMac != ownerMac
            val idMismatch = deviceId != null && deviceId > 0 && ownerId > 0 && deviceId != ownerId
            if (!macMismatch && !idMismatch) keys += KEY_PREFIX_IP + cleanIp
        } else if (!hasMacToken) {
            return ""
        }
        // Ties (tokens saved before sequence numbers existed) keep the MAC > id > IP order.
        return keys.mapNotNull { key -> prefs.getString(key, null)?.takeIf { it.isNotBlank() }?.let { key to it } }
            .maxByOrNull { (key, _) -> prefs.getLong(KEY_PREFIX_SEQ + key, 0) }
            ?.second.orEmpty()
    }

    fun clearSamsungToken(ipAddress: String) {
        val cleanIp = ipAddress.trim()
        prefs.edit().apply {
            remove(KEY_PREFIX_IP + cleanIp)
            remove(KEY_PREFIX_SEQ + KEY_PREFIX_IP + cleanIp)
            apply()
        }
    }

    fun getAllSavedIps(): Set<String> {
        return prefs.all.keys
            .filter { it.startsWith(KEY_PREFIX_IP) }
            .map { it.removePrefix(KEY_PREFIX_IP) }
            .toSet()
    }

    fun clearAllTokens() {
        // The client identity is shared by all Android TVs. Rotating it would invalidate
        // their existing authorizations, including after a process restart.
        val editor = prefs.edit()
        prefs.all.keys.filter { it != KEY_CLIENT_CERT_DER && it != KEY_CLIENT_KEY_PKCS8 }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    fun isAndroidTvPaired(ipAddress: String): Boolean {
        val cleanIp = ipAddress.trim()
        return prefs.getBoolean(KEY_PREFIX_ANDROID_PAIRED + cleanIp, false)
    }

    fun setAndroidTvPaired(ipAddress: String, paired: Boolean) {
        val cleanIp = ipAddress.trim()
        prefs.edit().putBoolean(KEY_PREFIX_ANDROID_PAIRED + cleanIp, paired).apply()
    }

    fun saveCertData(certDerBase64: String, privateKeyPkcs8Base64: String) {
        prefs.edit()
            .putString(KEY_CLIENT_CERT_DER, certDerBase64)
            .putString(KEY_CLIENT_KEY_PKCS8, privateKeyPkcs8Base64)
            .commit().also { check(it) { "Could not persist Android TV TLS identity" } }
    }

    fun getCertDer(): String? = prefs.getString(KEY_CLIENT_CERT_DER, null)
    fun getPrivateKeyPkcs8(): String? = prefs.getString(KEY_CLIENT_KEY_PKCS8, null)

    override fun getMacLogin(deviceId: Long): MacLogin? {
        val prefix = KEY_PREFIX_MAC_LOGIN + deviceId + "_"
        return MacLogin(
            username = prefs.getString(prefix + "user", null) ?: return null,
            privateKey = prefs.getString(prefix + "private_key", null) ?: return null,
            publicKey = prefs.getString(prefix + "public_key", null) ?: return null,
            hostKey = prefs.getString(prefix + "host_key", null) ?: return null
        )
    }

    override fun saveMacLogin(deviceId: Long, login: MacLogin) {
        val prefix = KEY_PREFIX_MAC_LOGIN + deviceId + "_"
        prefs.edit()
            .putString(prefix + "user", login.username)
            .putString(prefix + "private_key", login.privateKey)
            .putString(prefix + "public_key", login.publicKey)
            .putString(prefix + "host_key", login.hostKey)
            .commit().also { check(it) { "Could not persist Mac login" } }
    }

    override fun clearMacLogin(deviceId: Long) {
        val prefix = KEY_PREFIX_MAC_LOGIN + deviceId + "_"
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    companion object {
        private const val TAG = "TvTokenStore"
        private const val PREFS_NAME = "omni_remote_tokens"
        private const val KEY_PREFIX_IP = "samsung_token_ip_"
        private const val KEY_PREFIX_ID = "samsung_token_id_"
        private const val KEY_PREFIX_MAC = "samsung_token_mac_"
        private const val KEY_IP_OWNER_MAC = "samsung_ip_owner_mac_"
        private const val KEY_IP_OWNER_ID = "samsung_ip_owner_id_"
        private const val KEY_ID_OWNER_MAC = "samsung_id_owner_mac_"
        private const val KEY_PREFIX_ANDROID_PAIRED = "android_tv_paired_"
        private const val KEY_CLIENT_CERT_DER = "android_tv_client_cert_der"
        private const val KEY_CLIENT_KEY_PKCS8 = "android_tv_client_key_pkcs8"
        private const val KEY_PREFIX_MAC_LOGIN = "mac_login_"
        private const val KEY_SEQ = "samsung_token_seq"
        private const val KEY_PREFIX_SEQ = "samsung_token_seq_"

        @Volatile
        private var instance: TvTokenStore? = null

        fun getInstance(context: Context): TvTokenStore {
            return instance ?: synchronized(this) {
                instance ?: TvTokenStore(context).also { instance = it }
            }
        }
    }
}

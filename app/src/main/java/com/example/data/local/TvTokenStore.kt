package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.network.WakeOnLan

/**
 * Persistent token store for TV device authorization credentials.
 * Ensures Samsung Smart TV authorization tokens and Android TV pairing credentials
 * are permanently preserved across app restarts, re-scans, and device selections.
 */
class TvTokenStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun saveSamsungToken(ipAddress: String, token: String, deviceId: Long? = null, macAddress: String? = null) {
        if (token.isBlank()) return
        val cleanIp = ipAddress.trim()
        Log.d(TAG, "Persisting Samsung TV token for IP: $cleanIp (length: ${token.length})")

        prefs.edit().apply {
            putString(KEY_PREFIX_IP + cleanIp, token)
            if (deviceId != null && deviceId > 0) {
                putString(KEY_PREFIX_ID + deviceId, token)
                putLong(KEY_IP_OWNER_ID + cleanIp, deviceId)
            }
            WakeOnLan.formatMacAddress(macAddress)?.let { mac ->
                putString(KEY_PREFIX_MAC + mac, token)
                putString(KEY_IP_OWNER_MAC + cleanIp, mac)
                if (deviceId != null && deviceId > 0) putString(KEY_ID_OWNER_MAC + deviceId, mac)
            }
            apply()
        }
    }

    fun getSamsungToken(ipAddress: String, deviceId: Long? = null, macAddress: String? = null): String {
        val cleanIp = ipAddress.trim()
        // Stable hardware identity wins when DHCP assigns an old IP to another TV.
        WakeOnLan.formatMacAddress(macAddress)?.let { mac ->
            val token = prefs.getString(KEY_PREFIX_MAC + mac, null)
                ?: macAddress?.let { prefs.getString(KEY_PREFIX_MAC + it.trim().uppercase(), null) }
            if (!token.isNullOrBlank()) return token
        }
        if (deviceId != null && deviceId > 0) {
            val owner = prefs.getString(KEY_ID_OWNER_MAC + deviceId, null)
            val requested = WakeOnLan.formatMacAddress(macAddress)
            if (owner != null && requested != null && owner != requested) return ""
            val token = prefs.getString(KEY_PREFIX_ID + deviceId, null)
            if (!token.isNullOrBlank()) return token
        }
        val ownerMac = prefs.getString(KEY_IP_OWNER_MAC + cleanIp, null)
        val requestedMac = WakeOnLan.formatMacAddress(macAddress)
        if (requestedMac != null && ownerMac != null && requestedMac != ownerMac) return ""
        val ownerId = prefs.getLong(KEY_IP_OWNER_ID + cleanIp, 0)
        if (deviceId != null && deviceId > 0 && ownerId > 0 && deviceId != ownerId) return ""
        return prefs.getString(KEY_PREFIX_IP + cleanIp, "") ?: ""
    }

    fun clearSamsungToken(ipAddress: String) {
        val cleanIp = ipAddress.trim()
        prefs.edit().apply {
            remove(KEY_PREFIX_IP + cleanIp)
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

        @Volatile
        private var instance: TvTokenStore? = null

        fun getInstance(context: Context): TvTokenStore {
            return instance ?: synchronized(this) {
                instance ?: TvTokenStore(context).also { instance = it }
            }
        }
    }
}

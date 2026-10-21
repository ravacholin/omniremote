package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

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
            }
            if (!macAddress.isNullOrBlank()) {
                putString(KEY_PREFIX_MAC + macAddress.trim().uppercase(), token)
            }
            // Also store the most recent token as fallback
            putString(KEY_LAST_SAMSUNG_TOKEN, token)
            apply()
        }
    }

    fun getSamsungToken(ipAddress: String, deviceId: Long? = null, macAddress: String? = null): String {
        val cleanIp = ipAddress.trim()
        // 1. Try IP address match
        var token = prefs.getString(KEY_PREFIX_IP + cleanIp, null)
        if (!token.isNullOrBlank()) return token

        // 2. Try Device ID match
        if (deviceId != null && deviceId > 0) {
            token = prefs.getString(KEY_PREFIX_ID + deviceId, null)
            if (!token.isNullOrBlank()) return token
        }

        // 3. Try MAC address match
        if (!macAddress.isNullOrBlank()) {
            token = prefs.getString(KEY_PREFIX_MAC + macAddress.trim().uppercase(), null)
            if (!token.isNullOrBlank()) return token
        }

        // 4. If only one Samsung TV was ever paired on this network, use the last known token
        val lastToken = prefs.getString(KEY_LAST_SAMSUNG_TOKEN, "") ?: ""
        return lastToken
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
        prefs.edit().clear().apply()
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
            .apply()
    }

    fun getCertDer(): String? = prefs.getString(KEY_CLIENT_CERT_DER, null)
    fun getPrivateKeyPkcs8(): String? = prefs.getString(KEY_CLIENT_KEY_PKCS8, null)

    companion object {
        private const val TAG = "TvTokenStore"
        private const val PREFS_NAME = "omni_remote_tokens"
        private const val KEY_PREFIX_IP = "samsung_token_ip_"
        private const val KEY_PREFIX_ID = "samsung_token_id_"
        private const val KEY_PREFIX_MAC = "samsung_token_mac_"
        private const val KEY_LAST_SAMSUNG_TOKEN = "samsung_token_last"
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

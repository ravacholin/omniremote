package com.example.data.local

import android.content.Context

/** Optional ADB port belongs to the saved TV, never to the regular remote endpoint. */
class AdbSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("android_tv_adb", Context.MODE_PRIVATE)
    fun port(deviceId: Long): Int? = prefs.getInt("port_$deviceId", 0).takeIf { it in 1..65535 }
    fun save(deviceId: Long, port: Int) {
        require(deviceId > 0 && port in 1..65535)
        check(prefs.edit().putInt("port_$deviceId", port).commit()) { "No se pudo guardar la configuración ADB" }
    }
    fun clear(deviceId: Long) {
        check(prefs.edit().remove("port_$deviceId").commit()) { "No se pudo borrar la configuración ADB" }
    }
}

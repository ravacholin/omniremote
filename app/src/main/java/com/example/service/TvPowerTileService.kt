package com.example.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import com.example.MainActivity
import com.example.data.local.AppDatabase
import com.example.network.WakeOnLan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android Quick Settings Tile for 1-tap Wake-on-LAN / Power toggle.
 *
 * Allows users to turn on their Smart TV straight from the Quick Settings notification shade
 * without even opening the full app.
 */
class TvPowerTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val defaultTv = db.tvDeviceDao().getDefaultDevice()
                withContext(Dispatchers.Main) {
                    val tile = qsTile ?: return@withContext
                    tile.label = "Encender TV"
                    tile.state = if (defaultTv != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = defaultTv?.name ?: "Wake-on-LAN"
                        tile.contentDescription = "Encender ${defaultTv?.name ?: "TV"} con Wake-on-LAN"
                    }
                    tile.updateTile()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error updating power QS tile: ${e.message}")
            }
        }
    }

    // The Intent overload is required on API 24–33; API 34+ uses PendingIntent below.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()

        // Haptic feedback
        triggerHaptic()

        serviceScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val defaultTv = db.tvDeviceDao().getDefaultDevice()

            if (defaultTv == null) {
                // Open app to select/pair a TV
                withContext(Dispatchers.Main) {
                    val launchIntent = Intent(this@TvPowerTileService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val pendingIntent = PendingIntent.getActivity(
                            this@TvPowerTileService,
                            0,
                            launchIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        startActivityAndCollapse(pendingIntent)
                    } else {
                        @Suppress("DEPRECATION")
                        startActivityAndCollapse(launchIntent)
                    }
                }
                return@launch
            }

            // Provide visual feedback in the tile
            withContext(Dispatchers.Main) {
                qsTile?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        subtitle = "Enviando señal..."
                    }
                    updateTile()
                }
            }

            val result = WakeOnLan.powerOnTv(defaultTv, context = applicationContext)
            result.resolvedMac?.let { mac ->
                if (WakeOnLan.formatMacAddress(defaultTv.macAddress) == null) {
                    db.tvDeviceDao().updateMacAddress(defaultTv.id, mac)
                }
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    applicationContext,
                    if (result.success) "⚡ Ráfaga Wake-on-LAN enviada a ${defaultTv.name}" else result.message,
                    Toast.LENGTH_SHORT
                ).show()

                qsTile?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        subtitle = when {
                            result.success -> "Señal enviada"
                            result.resolvedMac == null -> "Falta MAC"
                            else -> "Error de red"
                        }
                    }
                    updateTile()
                }
            }

            delay(2500)

            withContext(Dispatchers.Main) {
                qsTile?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        subtitle = defaultTv.name
                    }
                    updateTile()
                }
            }
        }
    }

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(50)
                }
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "TvPowerTileService"
    }
}

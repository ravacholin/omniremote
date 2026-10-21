package com.example.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.example.MainActivity
import com.example.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android Quick Settings Tile for instant TV Remote access.
 *
 * Appears in the Android Quick Settings drop-down panel (pull down notification shade twice).
 * Shows the active or default Smart TV name as the tile subtitle.
 * Tapping it instantly collapses the quick panel and brings up OmniRemote.
 */
class TvRemoteTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val defaultTv = db.tvDeviceDao().getDefaultDevice()
                withContext(Dispatchers.Main) {
                    val tile = qsTile ?: return@withContext
                    tile.label = "Control TV"
                    tile.subtitle = defaultTv?.name ?: "Abrir control"
                    tile.state = if (defaultTv != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.contentDescription = "Abrir control remoto OmniRemote para ${defaultTv?.name ?: "TV"}"
                    }
                    tile.updateTile()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error updating remote QS tile: ${e.message}")
            }
        }
    }

    override fun onClick() {
        super.onClick()

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_FROM_QS_TILE, true)
        }

        val launchAction = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
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

        if (isLocked) {
            unlockAndRun {
                launchAction()
            }
        } else {
            launchAction()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "TvRemoteTileService"
        const val EXTRA_FROM_QS_TILE = "extra_from_qs_tile"
    }
}

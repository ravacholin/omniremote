package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.controller.RemoteSession
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey

object QuickRemoteNotification {
    const val CHANNEL = "quick_remote"
    const val ID = 7322 // Separate from Sleep.

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.quick_channel), NotificationManager.IMPORTANCE_LOW)
            channel.setSound(null, null)
            channel.enableVibration(false)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        ensureChannel(context)
        return Build.VERSION.SDK_INT < 26 || context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun build(context: Context, session: RemoteSession, manager: QuickRemoteManager): Notification? {
        val device = session.selectedDevice.value ?: return null
        val close = pending(context, manager, "close") ?: return null
        val state = session.controller.connectionState.value
        val connected = state is ConnectionState.Connected && state.ip == device.ipAddress
        val connectionStatus = session.powerStatus.value ?: when (state) {
            is ConnectionState.Connected -> context.getString(R.string.quick_connected)
            is ConnectionState.Connecting -> context.getString(R.string.quick_connecting)
            is ConnectionState.WaitingAuth -> context.getString(R.string.quick_authorize)
            else -> context.getString(R.string.quick_disconnected)
        }
        val media = session.macMedia.state.value
        val status = if (device.deviceType == DeviceType.MACOS) "$connectionStatus · ${media.label}" else connectionStatus
        fun views(layout: Int): RemoteViews = RemoteViews(context.packageName, layout).apply {
            for ((id, action) in listOf(R.id.quick_volume_up to "volume_up", R.id.quick_volume_down to "volume_down",
                R.id.quick_mute to "mute", R.id.quick_play_pause to "play_pause", R.id.quick_power to "power", R.id.quick_close to "close")) {
                pending(context, manager, action)?.let { setOnClickPendingIntent(id, it) }
                val enabled = action == "close" || (action == "power" && state !is ConnectionState.Connecting &&
                    state !is ConnectionState.WaitingAuth && session.powerStatus.value?.startsWith("Enviando") != true) ||
                    (action != "power" && connected && (action != "play_pause" || device.deviceType != DeviceType.MACOS || media.canSend(RemoteKey.PLAY_PAUSE)))
                setBoolean(id, "setEnabled", enabled)
            }
            if (layout == R.layout.quick_remote_expanded) {
                setTextViewText(R.id.quick_status, status)
                if (device.deviceType == DeviceType.MACOS) {
                    setTextViewText(R.id.quick_power_label, context.getString(R.string.quick_mac_power))
                    setContentDescription(R.id.quick_power, context.getString(R.string.quick_mac_power_desc))
                }
            }
        }
        val open = PendingIntent.getActivity(context, 7322,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else {
            @Suppress("DEPRECATION")
            Notification.Builder(context).setPriority(Notification.PRIORITY_LOW)
        }
        return builder.setSmallIcon(R.drawable.ic_qs_remote_tile)
            .setContentTitle(device.name).setSubText(device.name).setContentText(status)
            .setContentIntent(open).setDeleteIntent(close)
            .setCustomContentView(views(R.layout.quick_remote_compact))
            .setCustomBigContentView(views(R.layout.quick_remote_expanded))
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false)
            .build()
    }

    private fun pending(context: Context, manager: QuickRemoteManager, action: String): PendingIntent? =
        manager.actionIntent(action)?.let {
            PendingIntent.getService(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
}

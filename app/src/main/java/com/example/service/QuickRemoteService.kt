package com.example.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.controller.RemoteSession
import com.example.data.model.RemoteKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class QuickRemoteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager by lazy { QuickRemoteManager.getInstance(this) }
    private val session by lazy { RemoteSession.getInstance(this) }
    private var startToken: String? = null
    private var started = false
    private var observation: Job? = null
    private var blockReceiverRegistered = false
    private val blockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT < 28) return
            val applies = intent.action == NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED ||
                (intent.action == NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED &&
                    intent.getStringExtra(NotificationManager.EXTRA_NOTIFICATION_CHANNEL_ID) == QuickRemoteNotification.CHANNEL)
            if (applies && intent.getBooleanExtra(NotificationManager.EXTRA_BLOCKED_STATE, false)) close()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 28) {
            val filter = IntentFilter(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED).apply {
                addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(this, blockReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            blockReceiverRegistered = true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == QuickRemoteManager.START) {
            val incoming = intent.getStringExtra(QuickRemoteManager.TOKEN)
            if (!manager.attach(incoming)) {
                stopSelf()
                return START_NOT_STICKY
            }
            if (!started) {
                startToken = incoming
                val notification = QuickRemoteNotification.build(this, session, manager)
                if (notification == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        startForeground(QuickRemoteNotification.ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    } else startForeground(QuickRemoteNotification.ID, notification)
                } catch (error: RuntimeException) {
                    Log.w("QuickRemote", "No se pudo mostrar el mini mando", error)
                    manager.dismiss()
                    stopSelf()
                    return START_NOT_STICKY
                }
                started = true
                observeSession()
            }
            return START_NOT_STICKY
        }
        // A stale PendingIntent must not create a control session after process death.
        if (!started || intent == null || !manager.valid(intent)) {
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        if (!QuickRemoteNotification.allowed(this)) {
            close()
            return START_NOT_STICKY
        }
        when (intent.action) {
            "close" -> close()
            "volume_up" -> session.sendKey(RemoteKey.VOL_UP)
            "volume_down" -> session.sendKey(RemoteKey.VOL_DOWN)
            "mute" -> session.sendKey(RemoteKey.MUTE)
            "play_pause" -> session.sendKey(RemoteKey.PLAY_PAUSE)
            "power" -> session.powerToggle()
        }
        return START_NOT_STICKY
    }

    private fun observeSession() {
        observation = scope.launch {
            combine(session.selectedDevice, session.controller.connectionState, session.powerStatus, session.macMedia.state) { _, _, _, _ -> Unit }
                .collect {
                    val notification = QuickRemoteNotification.build(this@QuickRemoteService, session, manager)
                    if (!manager.isRunning || notification == null) {
                        // No selected device is an automatic stop, not the user's "hide until reopening".
                        stopControls()
                    } else if (!QuickRemoteNotification.allowed(this@QuickRemoteService)) {
                        close()
                    } else getSystemService(NotificationManager::class.java).notify(QuickRemoteNotification.ID, notification)
                }
        }
    }

    private fun close() {
        // Stop observing before removal: a pending emission must not dismiss the next opening.
        observation?.cancel()
        manager.dismiss()
        stopControls()
    }

    private fun stopControls() {
        observation?.cancel()
        observation = null
        started = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (blockReceiverRegistered) {
            unregisterReceiver(blockReceiver)
            blockReceiverRegistered = false
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        manager.serviceStopped(startToken)
        super.onDestroy()
    }
}

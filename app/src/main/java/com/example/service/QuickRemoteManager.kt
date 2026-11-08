package com.example.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.controller.RemoteSession
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

/** Controls the lifetime of one user-requested mini remote, never restored on boot. */
class QuickRemoteManager private constructor(private val context: Context) {
    private val session = RemoteSession.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false
    private var dismissed = false
    private var requested = false
    private var attached = false
    private var token: String? = null
    val isRunning: Boolean get() = requested || attached

    init {
        scope.launch {
            combine(session.selectedDevice, session.controller.connectionState) { _, _ -> Unit }
                .collect { refresh() }
        }
    }

    fun onAppForeground() {
        if (!foreground) dismissed = false
        foreground = true
        refresh()
    }

    fun onAppBackground(changingConfigurations: Boolean) {
        if (changingConfigurations) return
        foreground = false
        if (!isRunning) session.disconnectForBackground()
    }

    fun refresh() {
        val device = session.selectedDevice.value
        if (device == null || !QuickRemoteNotification.allowed(context)) {
            stop()
            return
        }
        if (!foreground || dismissed || isRunning) return
        val state = session.controller.connectionState.value
        if (state !is ConnectionState.Connected || state.ip != device.ipAddress) return
        val fresh = UUID.randomUUID().toString()
        token = fresh
        requested = true
        try {
            ContextCompat.startForegroundService(context,
                Intent(context, QuickRemoteService::class.java).setAction(START).putExtra(TOKEN, fresh))
        } catch (error: RuntimeException) {
            Log.w("QuickRemote", "No se pudo iniciar el mini mando", error)
            dismissed = true
            stop()
        }
    }

    fun dismiss() {
        dismissed = true
        stop()
    }

    private fun stop() {
        val wasRunning = isRunning
        requested = false
        attached = false
        token = null
        if (wasRunning) context.stopService(Intent(context, QuickRemoteService::class.java))
        if (!foreground) session.disconnectForBackground()
    }

    internal fun attach(startToken: String?): Boolean {
        if (!requested || dismissed || startToken == null || token != startToken ||
            session.selectedDevice.value == null || !QuickRemoteNotification.allowed(context)) return false
        attached = true
        return true
    }

    internal fun serviceStopped(startToken: String?) {
        if (token != startToken || startToken == null) return
        // An OS stop with a target waits for reopening; removing the last target stays eligible.
        if (session.selectedDevice.value != null) dismissed = true
        requested = false
        attached = false
        token = null
        if (!foreground) session.disconnectForBackground()
    }

    internal fun valid(intent: Intent): Boolean {
        val device = session.selectedDevice.value ?: return false
        val currentSession = attached && requested && !dismissed && token != null && intent.getStringExtra(TOKEN) == token
        if (!currentSession) return false
        // Closing affects the mini remote itself, including a just-replaced SystemUI view.
        if (intent.action == "close") return true
        if (intent.action == "play_pause" && device.deviceType == DeviceType.MACOS &&
            intent.getLongExtra(MEDIA_REVISION, -1) != session.macMedia.state.value.revision) return false
        return intent.getLongExtra(DEVICE, -1) == device.id &&
            intent.getStringExtra(IP) == device.ipAddress &&
            intent.getLongExtra(GENERATION, -1) == session.targetGeneration
    }

    fun actionIntent(action: String): Intent? {
        val device = session.selectedDevice.value ?: return null
        val current = token ?: return null
        if (!requested || dismissed) return null
        val mediaRevision = if (action == "play_pause" && device.deviceType == DeviceType.MACOS) session.macMedia.state.value.revision else -1L
        return Intent(context, QuickRemoteService::class.java).setAction(action)
            .setData(Uri.Builder().scheme("omniremote").authority("quick")
                .appendPath(current).appendPath(session.targetGeneration.toString())
                .appendPath(device.id.toString()).appendPath(action).appendPath(mediaRevision.toString()).build())
            .putExtra(TOKEN, current).putExtra(DEVICE, device.id)
            .putExtra(IP, device.ipAddress).putExtra(GENERATION, session.targetGeneration).putExtra(MEDIA_REVISION, mediaRevision)
    }

    fun shouldRequestPermission(): Boolean = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
        !context.getSharedPreferences("quick_remote", Context.MODE_PRIVATE).getBoolean("permission_asked", false)

    fun markPermissionRequested() {
        context.getSharedPreferences("quick_remote", Context.MODE_PRIVATE).edit()
            .putBoolean("permission_asked", true).apply()
    }

    companion object {
        internal const val START = "com.example.quick.START"
        internal const val TOKEN = "token"
        private const val DEVICE = "device_id"
        private const val IP = "device_ip"
        private const val GENERATION = "target_generation"
        private const val MEDIA_REVISION = "mac_media_revision"
        @Volatile private var instance: QuickRemoteManager? = null
        fun getInstance(context: Context): QuickRemoteManager = synchronized(this) {
            val app = context.applicationContext
            instance?.takeIf { it.context === app }
                ?: QuickRemoteManager(app).also { instance = it }
        }
    }
}

package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.model.DeviceType
import com.example.sleep.SleepTimerManager
import com.example.sleep.TvSleepPowerOff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Starts only at expiry, so a 2-hour Sleep does not keep a foreground service running. */
class SleepTimerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var execution: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var executionToken: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (execution?.isActive == true) return START_NOT_STICKY
        val notification = notification("Apagando la TV…", ongoing = true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val manager = SleepTimerManager.getInstance(this)
        val timer = intent?.data?.lastPathSegment?.let { manager.consumeDue(it) }
        if (timer == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        executionToken = timer.token
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:SleepPowerOff")
            .apply { acquire(60_000) }
        execution = scope.launch {
            val message = try {
                val device = withContext(Dispatchers.IO) {
                    AppDatabase.getInstance(applicationContext).tvDeviceDao().getDeviceById(timer.deviceId)
                }
                when {
                    device == null -> "Sleep cancelado: la TV ya no está guardada"
                    TvSleepPowerOff(applicationContext).powerOff(device) { manager.isExecutionCurrent(timer.token) } ->
                        if (device.deviceType == DeviceType.ANDROID_TV) "Android TV confirmó reposo en ${device.name}"
                        else "Orden de apagado enviada a ${device.name}"
                    else -> "No se pudo confirmar el apagado de ${device.name}. Revisá la conexión y la autorización del dispositivo."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                "No se pudo apagar ${timer.deviceName}. Revisá la conexión con la TV."
            }
            try {
                manager.finishExecution(timer.token, message)
            } finally {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        executionToken?.let { SleepTimerManager.getInstance(this).finishExecution(it, "Sleep no pudo completar el apagado de la TV") }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        executionToken?.let { SleepTimerManager.getInstance(this).finishExecution(it, "Sleep no pudo completar el apagado de la TV") }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun notification(message: String, ongoing: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sleep de la TV", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setSmallIcon(R.drawable.ic_qs_power_tile)
            .setContentTitle("Sleep")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
    }

    companion object {
        private const val CHANNEL = "tv_sleep"
        private const val NOTIFICATION_ID = 7321
    }
}

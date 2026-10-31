package com.example.sleep

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

data class SleepTimerState(
    val token: String,
    val deviceId: Long,
    val deviceName: String,
    val minutes: Int,
    val deadlineElapsed: Long,
    val bootCount: Int
) {
    /** Use the alarm's monotonic deadline, including time spent with the phone asleep. */
    fun remainingSeconds(nowElapsed: Long = SystemClock.elapsedRealtime()): Long {
        val millis = (deadlineElapsed - nowElapsed).coerceAtLeast(0L)
        return millis / 1_000 + if (millis % 1_000 == 0L) 0 else 1
    }
}

/** One OS-owned timer. No Activity, ViewModel or live TV session needs to survive. */
class SleepTimerManager(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE)
    private val alarms = this.context.getSystemService(AlarmManager::class.java)
    private val _timer = MutableStateFlow(readTimer())
    val timer = _timer.asStateFlow()
    private val _result = MutableStateFlow(prefs.getString("result", null))
    val result = _result.asStateFlow()
    // A live service owns this claim. It must remain cancellable while connecting.
    private var execution: SleepTimerState? = null

    val canScheduleExact: Boolean
        get() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    @Synchronized
    fun advance(device: TvDeviceEntity): SleepTimerState? {
        require(device.id > 0 && device.deviceType != DeviceType.MACOS) { "Seleccioná una TV guardada" }
        val previous = _timer.value
        val currentMinutes = previous?.takeIf { it.deadlineElapsed > SystemClock.elapsedRealtime() }?.minutes
        val minutes = when (currentMinutes) {
            30 -> 60
            60 -> 120
            120 -> { cancel(); return null }
            else -> 30
        }
        checkExactPermission()
        val next = SleepTimerState(
            token = UUID.randomUUID().toString(), deviceId = device.id, deviceName = device.name,
            minutes = minutes, deadlineElapsed = SystemClock.elapsedRealtime() + minutes * 60_000L,
            bootCount = bootCount()
        )
        val operation = operation(next.token)
        alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next.deadlineElapsed, operation)
        if (!prefs.edit().putString("timer", encode(next)).remove("result").commit()) {
            alarms.cancel(operation)
            operation.cancel()
            error("No se pudo guardar el temporizador")
        }
        _timer.value = next
        execution = null
        _result.value = null
        previous?.let { cancelAlarm(it.token) }
        return next
    }

    @Synchronized
    fun cancel() {
        val previous = _timer.value
        check(prefs.edit().remove("timer").remove("result").commit()) { "No se pudo cancelar Sleep" }
        _timer.value = null
        execution = null
        _result.value = null
        previous?.let { cancelAlarm(it.token) }
    }

    @Synchronized
    fun cancelForDevice(id: Long) {
        if (_timer.value?.deviceId == id || execution?.deviceId == id) cancel()
    }

    /** Claims an expired generation exactly once. Old, early and duplicate deliveries do nothing. */
    @Synchronized
    fun consumeDue(token: String): SleepTimerState? {
        val current = _timer.value ?: return null
        if (current.token != token || current.bootCount != bootCount() ||
            SystemClock.elapsedRealtime() < current.deadlineElapsed) return null
        check(prefs.edit().remove("timer").commit()) { "No se pudo iniciar el apagado" }
        execution = current
        _timer.value = null
        cancelAlarm(current.token)
        return current
    }

    @Synchronized
    fun isExecutionCurrent(token: String): Boolean = execution?.token == token

    @Synchronized
    fun finishExecution(token: String, message: String) {
        if (execution?.token != token) return
        execution = null
        reportResult(message)
    }

    @Synchronized
    fun reportResult(message: String?) {
        // Do not cover a newer timer's status with the outcome of an older execution.
        if (_timer.value != null && message != null) return
        prefs.edit().apply { if (message == null) remove("result") else putString("result", message) }.commit()
        _result.value = message
    }

    private fun checkExactPermission() {
        if (!canScheduleExact) throw SecurityException("Permití Alarmas y recordatorios para activar Sleep")
    }

    private fun operation(token: String): PendingIntent {
        val intent = Intent().setClassName(context, "com.example.service.SleepTimerService")
            .setData(Uri.parse("omniremote://sleep/$token"))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(context, 0, intent, flags)
        else PendingIntent.getService(context, 0, intent, flags)
    }

    private fun cancelAlarm(token: String) {
        val operation = operation(token)
        alarms.cancel(operation)
        operation.cancel()
    }

    private fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)

    private fun readTimer(): SleepTimerState? {
        val saved = prefs.getString("timer", null) ?: return null
        val state = runCatching {
            val json = JSONObject(saved)
            SleepTimerState(json.getString("token"), json.getLong("deviceId"), json.getString("deviceName"),
                json.getInt("minutes"), json.getLong("deadlineElapsed"), json.getInt("bootCount"))
        }.getOrNull()
        if (state == null || state.bootCount != bootCount()) {
            state?.let { cancelAlarm(it.token) }
            prefs.edit().remove("timer").commit()
            return null
        }
        return state
    }

    private fun encode(state: SleepTimerState) = JSONObject().apply {
        put("token", state.token)
        put("deviceId", state.deviceId)
        put("deviceName", state.deviceName)
        put("minutes", state.minutes)
        put("deadlineElapsed", state.deadlineElapsed)
        put("bootCount", state.bootCount)
    }.toString()

    companion object {
        @Volatile private var instance: SleepTimerManager? = null
        fun getInstance(context: Context): SleepTimerManager = instance ?: synchronized(this) {
            instance ?: SleepTimerManager(context).also { instance = it }
        }
    }
}

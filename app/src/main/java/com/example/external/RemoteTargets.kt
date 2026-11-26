package com.example.external

import android.content.Context
import android.os.PowerManager
import com.example.BuildConfig
import com.example.controller.AndroidAdbBrightness
import com.example.controller.RemoteSession
import com.example.controller.TargetRemoteConnection
import com.example.controller.TargetRemoteConnection.Readiness
import com.example.data.local.AdbSettingsStore
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.data.model.TvAppEntity
import com.example.data.model.TvDeviceEntity
import com.example.data.model.hasSameControlEndpoint
import com.example.network.AndroidTvProtocol
import com.example.network.AndroidTvVolume
import com.example.network.MacMediaProtocol
import com.example.network.MacMediaScripts
import com.example.network.MacMediaSource
import com.example.network.MacPlayback
import com.example.network.MacResult
import com.example.network.MacScripts
import com.example.network.SamsungBluetoothAudio
import com.example.sleep.SleepTimerManager
import com.example.sleep.SleepTimerState
import com.example.sleep.TvSleepPowerOff
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

internal class RemoteTargets(private val context: Context, private val session: RemoteSession) {
    private val devices = session.repository
    private val apps = session.apps
    private val adb = AdbSettingsStore(context)
    private val sleep = SleepTimerManager.getInstance(context)
    private val powerOff = TvSleepPowerOff(context)
    private val dimmerPackage = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    suspend fun listDevices(afterId: Long): RemoteReply {
        val rows = devices.listRegistered(afterId, PAGE_SIZE + 1)
        return page("devices", rows, TvDeviceEntity::id, ::deviceSummary)
    }

    suspend fun getDevice(deviceId: Long): RemoteReply {
        val target = registeredDevice(deviceId) ?: return RemoteReply.rejected("UNKNOWN_DEVICE")
        val dimmer = dimmer(target)
        val available = supportedCommands(target.deviceType, dimmer != null, adb.port(deviceId) != null)
        val state = when (target.deviceType) {
            DeviceType.MACOS -> listOf("controlReachable", "volume", "muted")
            DeviceType.ANDROID_TV -> listOf("controlReachable", "power", "volume", "volumeLevel", "volumeMax", "muted",
                "sleepTimer", "sleepPowerOffPending")
            DeviceType.SAMSUNG_TIZEN -> listOf("controlReachable", "sleepTimer", "sleepPowerOffPending")
        }
        return RemoteReply.verified(deviceSummary(target)
            .put("commands", JSONArray(available.map { it.name }))
            .put("keys", JSONArray(supportedKeys(target.deviceType).map { it.name }))
            .put("stateFields", JSONArray(state))
            .put("canScheduleExactAlarms", target.deviceType != DeviceType.MACOS && sleep.canScheduleExact))
    }

    suspend fun listApps(deviceId: Long, afterId: Long): RemoteReply {
        if (registeredDevice(deviceId) == null) return RemoteReply.rejected("UNKNOWN_DEVICE")
        val rows = apps.listRegistered(deviceId, afterId, PAGE_SIZE + 1)
        return page("apps", rows, TvAppEntity::id) {
            JSONObject().put("appId", it.id).put("name", it.appName.take(256))
                .put("category", it.category.take(128)).put("favorite", it.isFavorite)
        }
    }

    suspend fun dispatch(request: RemoteRequest, allowed: () -> Boolean): RemoteReply {
        val target = registeredDevice(request.deviceId) ?: return RemoteReply.rejected("UNKNOWN_DEVICE")
        val dimmer = if (request.command in setOf(Command.OPEN_BRIGHTNESS, Command.OPEN_BRIGHTNESS_ADB)) dimmer(target) else null
        if (request.command !in supportedCommands(target.deviceType, dimmer != null, adb.port(target.id) != null) ||
            request.command == Command.KEY && request.key() !in supportedKeys(target.deviceType)) {
            return RemoteReply.unsupported
        }

        val app = if (request.command == Command.OPEN_APP) {
            apps.getRegisteredApp(target.id, request.appId()) ?: return RemoteReply.rejected("UNKNOWN_APP")
        } else dimmer
        if (!target.hasSameControlEndpoint(registeredDevice(target.id))) return RemoteReply.rejected("TARGET_CHANGED")
        val lock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:ExternalControl")
        lock.setReferenceCounted(false)
        lock.acquire(RemoteCommandExecutor.TIMEOUT_MS)
        try {
            if (!allowed()) return RemoteReply.rejected("DEADLINE_EXCEEDED")
            return when (request.command) {
                Command.POWER_ON -> wake(target)
                Command.POWER_OFF -> if (target.deviceType == DeviceType.MACOS) {
                    connected(target, request, app, allowed)
                } else sleepPowerOff(target, allowed)
                Command.OPEN_BRIGHTNESS_ADB -> {
                    val port = adb.port(target.id) ?: return RemoteReply.unsupported
                    session.controller.launchAdbDimmer(target.ipAddress, port, checkNotNull(app).appIdentifier.trim(), allowSetup = false)
                    RemoteReply.sent
                }
                Command.SLEEP_FOR, Command.SLEEP_AT, Command.SLEEP_CANCEL, Command.SLEEP_EXTEND ->
                    changeTimer(target, request, allowed)
                else -> connected(target, request, app, allowed)
            }
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    private suspend fun connected(
        device: TvDeviceEntity, request: RemoteRequest, app: TvAppEntity?, allowed: () -> Boolean
    ): RemoteReply {
        val target = session.controller.forSavedTarget(device)
        return target.use { ready ->
            if (!device.hasSameControlEndpoint(registeredDevice(device.id))) return@use RemoteReply.rejected("TARGET_CHANGED")
            if (request.command == Command.GET_STATE) return@use state(target, ready, allowed)
            if (ready != Readiness.READY) return@use connectionError(ready)
            if (!allowed()) return@use RemoteReply.rejected("DEADLINE_EXCEEDED")
            if (device.deviceType == DeviceType.MACOS) return@use mac(target, request, allowed)
            when (request.command) {
                Command.KEY -> transmission(if (device.deviceType == DeviceType.ANDROID_TV)
                    target.android.sendKey(request.key(), allowed) else target.samsung.sendKey(request.key()))
                Command.TEXT -> when {
                    device.deviceType == DeviceType.SAMSUNG_TIZEN -> transmission(target.samsung.sendText(request.text()))
                    target.android.supportsFeature(AndroidTvProtocol.FEATURE_IME) == false -> RemoteReply.unsupported
                    else -> transmission(target.android.sendText(request.text(), allowed))
                }
                Command.SET_VOLUME, Command.SET_MUTE -> adjustTvVolume(target, request, allowed)
                Command.OPEN_APP, Command.OPEN_BRIGHTNESS -> openApp(target, checkNotNull(app), allowed)
                Command.OPEN_BLUETOOTH_AUDIO -> transmission(SamsungBluetoothAudio.open {
                    allowed() && target.samsung.isConnected && target.samsung.sendKey(it)
                })
                else -> RemoteReply.unsupported
            }
        }
    }

    private suspend fun wake(device: TvDeviceEntity): RemoteReply {
        val outcome = session.wakeTarget(device)
        if (outcome.resolvedMac == null) return RemoteReply.rejected("MAC_ADDRESS_REQUIRED")
        if (!outcome.success) return RemoteReply.uncertain("WAKE_NOT_CONFIRMED")
        // Give Samsung time to wake before another external command connects.
        if (device.deviceType == DeviceType.SAMSUNG_TIZEN) delay(BuildConfig.SAMSUNG_WAKE_RECONNECT_DELAY_MS)
        return RemoteReply.sent
    }

    private suspend fun sleepPowerOff(device: TvDeviceEntity, allowed: () -> Boolean): RemoteReply =
        when (powerOff.execute(device, allowed) { device.hasSameControlEndpoint(registeredDevice(device.id)) }) {
            TvSleepPowerOff.Outcome.VERIFIED -> RemoteReply.verified(JSONObject().put("power", false))
            TvSleepPowerOff.Outcome.SENT -> RemoteReply.sent
            TvSleepPowerOff.Outcome.UNCERTAIN -> RemoteReply.uncertain("POWER_OFF_NOT_CONFIRMED")
            TvSleepPowerOff.Outcome.UNREACHABLE -> connectionError(Readiness.UNREACHABLE)
            TvSleepPowerOff.Outcome.AUTHORIZATION_REQUIRED -> connectionError(Readiness.AUTHORIZATION_REQUIRED)
            TvSleepPowerOff.Outcome.IDENTITY_CHANGED -> RemoteReply.rejected("TARGET_IDENTITY_CHANGED")
            TvSleepPowerOff.Outcome.TARGET_CHANGED -> RemoteReply.rejected("TARGET_CHANGED")
            TvSleepPowerOff.Outcome.UNSUPPORTED -> RemoteReply.unsupported
        }

    private suspend fun openApp(target: TargetRemoteConnection, app: TvAppEntity, allowed: () -> Boolean): RemoteReply {
        if (target.device.deviceType == DeviceType.SAMSUNG_TIZEN) {
            return transmission(target.samsung.launchAppById(target.device.ipAddress,
                app.appIdentifier, external = true, canSend = allowed).isSuccess)
        }
        if (isDimmer(app)) {
            val pkg = app.appIdentifier.trim()
            if (!dimmerPackage.matches(pkg)) return RemoteReply.rejected("INVALID_APP_CONFIGURATION")
            if (target.android.supportsFeature(AndroidTvProtocol.APP_LINK_FEATURE) == false) return RemoteReply.unsupported
            // Remote v2 cannot attest which button has focus. Leave confirmation to the TV user.
            val sent = target.android.launchAppLink("https://play.google.com/store/apps/details?id=$pkg", allowed)
            return if (sent) RemoteReply("SENT", "STORE_OPEN_REQUESTED") else transmission(false)
        }
        var storeOpened = false
        val sent = target.android.launchAppByPackage(app.appIdentifier, app.deepLinkUrl, allowed) { storeOpened = true }
        return if (sent && storeOpened) RemoteReply("SENT", "STORE_OPEN_REQUESTED") else transmission(sent)
    }

    private suspend fun adjustTvVolume(
        target: TargetRemoteConnection, request: RemoteRequest, allowed: () -> Boolean
    ): RemoteReply {
        if (target.android.supportsFeature(AndroidTvProtocol.FEATURE_VOLUME) == false) return RemoteReply.unsupported
        val result = when (request.command) {
            Command.SET_VOLUME -> target.android.adjustVolume(request.number(), null, allowed)
            else -> target.android.adjustVolume(null, request.muted(), allowed)
        }
        val observed = result.volume?.let { volumeData(JSONObject(), it) }
        return when {
            result.verified -> RemoteReply.verified(observed)
            observed != null -> RemoteReply("UNCERTAIN", "STATE_MISMATCH", observed)
            else -> RemoteReply.uncertain("VOLUME_NOT_OBSERVED")
        }
    }

    private fun volumeData(data: JSONObject, volume: AndroidTvVolume): JSONObject =
        data.put("volume", volume.percent).put("volumeLevel", volume.level)
            .put("volumeMax", volume.max).put("muted", volume.muted)

    private suspend fun state(target: TargetRemoteConnection, ready: Readiness, allowed: () -> Boolean): RemoteReply {
        val device = target.device
        val data = JSONObject()
        if (device.deviceType != DeviceType.MACOS) synchronized(sleep) {
            data.put("sleepTimer", sleep.timer.value?.takeIf { it.deviceId == device.id }?.let(::timerData) ?: JSONObject.NULL)
            data.put("sleepPowerOffPending", sleep.timer.value == null && sleep.occupiedByDevice() == device.id)
        }
        if (ready != Readiness.READY) return RemoteReply("UNCERTAIN", connectionError(ready).code, data)
        data.put("controlReachable", true)
        when (device.deviceType) {
            DeviceType.ANDROID_TV -> {
                target.android.volumeReport()?.let { volumeData(data, it) }
                data.put("power", target.android.observedPower())
            }
            DeviceType.MACOS -> {
                val reply = target.mac.run(MacScripts.readVolume, allowed) as? MacResult.Ok
                val volume = reply?.let { MacScripts.parseVolume(it.output) }
                    ?: return RemoteReply("UNCERTAIN", "VOLUME_NOT_OBSERVED", data)
                data.put("volume", volume.level).put("muted", volume.muted)
            }
            DeviceType.SAMSUNG_TIZEN -> Unit
        }
        return RemoteReply.verified(data)
    }

    private fun changeTimer(device: TvDeviceEntity, request: RemoteRequest, allowed: () -> Boolean): RemoteReply =
        synchronized(sleep) {
            if (!allowed()) return@synchronized RemoteReply.rejected("DEADLINE_EXCEEDED")
            val owner = sleep.occupiedByDevice()
            try {
                when (request.command) {
                    Command.SLEEP_CANCEL -> {
                        sleep.cancelForDevice(device.id)
                        RemoteReply.verified(JSONObject().put("sleepTimer", JSONObject.NULL))
                    }
                    Command.SLEEP_EXTEND -> {
                        val current = sleep.timer.value?.takeIf { it.deviceId == device.id }
                        val next = current?.let { sleep.extend(it.token) }
                            ?: return@synchronized RemoteReply.rejected("NO_ACTIVE_TIMER")
                        RemoteReply.verified(JSONObject().put("sleepTimer", timerData(next)))
                    }
                    Command.SLEEP_FOR, Command.SLEEP_AT -> {
                        if (owner != null && owner != device.id) return@synchronized RemoteReply.rejected("TIMER_OTHER_DEVICE")
                        val next = if (request.command == Command.SLEEP_FOR) sleep.scheduleFor(device, request.number())
                        else sleep.scheduleAt(device, request.number(), request.number(1))
                        RemoteReply.verified(JSONObject().put("sleepTimer", timerData(next)))
                    }
                    else -> RemoteReply.unsupported
                }
            } catch (_: SecurityException) {
                RemoteReply.rejected("EXACT_ALARM_PERMISSION_REQUIRED")
            }
        }

    private fun mac(target: TargetRemoteConnection, request: RemoteRequest, allowed: () -> Boolean): RemoteReply {
        if (request.command in setOf(Command.MAC_MEDIA_LIST, Command.MAC_MEDIA_REVEAL,
                Command.MAC_MEDIA_STATE, Command.MAC_MEDIA_KEY)) return macMedia(target, request, allowed)
        val script = when (request.command) {
            Command.KEY -> MacScripts.forExternalKey(request.key())
            Command.POWER_OFF -> MacScripts.forKey(RemoteKey.POWER_OFF)
            Command.SET_VOLUME -> MacScripts.setVolume(request.number())
            Command.SET_MUTE -> MacScripts.setMute(request.muted())
            Command.MAC_OPEN_LINK -> try { MacScripts.openLink(request.text()) } catch (_: IllegalArgumentException) {
                return RemoteReply.rejected("INVALID_URL")
            }
            else -> null
        } ?: return RemoteReply.unsupported
        return when (val reply = target.mac.run(script, allowed)) {
            is MacResult.LoginNeeded -> connectionError(Readiness.AUTHORIZATION_REQUIRED)
            is MacResult.Failed -> RemoteReply.uncertain("MAC_COMMAND_NOT_CONFIRMED")
            is MacResult.Ok -> {
                val readsVolume = request.command == Command.SET_VOLUME || request.command == Command.SET_MUTE ||
                    request.command == Command.KEY && MacScripts.reportsVolume(request.key())
                if (readsVolume) {
                    val volume = MacScripts.parseVolume(reply.output) ?: return RemoteReply.uncertain("VOLUME_NOT_OBSERVED")
                    val observed = JSONObject().put("volume", volume.level).put("muted", volume.muted)
                    if (request.command == Command.SET_VOLUME && request.number() != volume.level ||
                        request.command == Command.SET_MUTE && request.muted() != volume.muted) {
                        RemoteReply("UNCERTAIN", "STATE_MISMATCH", observed)
                    } else RemoteReply.verified(observed)
                } else if (reply.output.trim() == MacScripts.NO_PLAYER) RemoteReply.rejected("NO_MEDIA")
                else RemoteReply.sent
            }
        }
    }

    private fun macMedia(target: TargetRemoteConnection, request: RemoteRequest, allowed: () -> Boolean): RemoteReply {
        val discovered = target.mac.run(MacMediaScripts.discover, allowed) as? MacResult.Ok
            ?: return RemoteReply.uncertain("MEDIA_DISCOVERY_FAILED")
        val list = MacMediaProtocol.parse(discovered.output)
        if (list.error != null) return RemoteReply.uncertain("MEDIA_DISCOVERY_FAILED")
        val sources = list.sources.filter { it.id.length <= 128 }
        if (request.command == Command.MAC_MEDIA_LIST) {
            return RemoteReply.verified(JSONObject().put("sources", JSONArray(sources.map(::mediaData)))
                .put("issues", JSONArray(list.issues.map { it.code }.filter { it in MEDIA_ISSUES }.distinct())))
        }
        val source = sources.firstOrNull { it.id == request.text() } ?: return RemoteReply.rejected("UNKNOWN_SOURCE")
        val script = if (request.command == Command.MAC_MEDIA_REVEAL) MacMediaScripts.reveal(source)
            else MacMediaScripts.inspect(source)
        val reply = (target.mac.run(script, allowed) as? MacResult.Ok)?.let { MacMediaProtocol.parse(it.output) }
            ?: return RemoteReply.uncertain("MEDIA_NOT_CONFIRMED")
        if (reply.revealed && request.command == Command.MAC_MEDIA_REVEAL) return RemoteReply.sent
        val observed = reply.source?.takeIf { it.verified }
            ?: return RemoteReply.uncertain("MEDIA_NOT_OBSERVED")
        if (request.command == Command.MAC_MEDIA_STATE) return RemoteReply.verified(mediaData(observed))
        if (!observed.supports(request.key())) return RemoteReply.unsupported
        val action = MacMediaScripts.command(observed, request.key())
        val result = (target.mac.run(action, allowed) as? MacResult.Ok)?.let { MacMediaProtocol.parse(it.output) }
            ?: return RemoteReply.uncertain("MEDIA_NOT_CONFIRMED")
        if (result.error?.code in listOf("STALE_SOURCE", "SOURCE_GONE", "CHROME_RESTARTED")) {
            return RemoteReply.rejected("SOURCE_CHANGED")
        }
        val report = result.source?.takeIf { it.verified }
            ?: return RemoteReply.uncertain("MEDIA_NOT_CONFIRMED")
        val expected = when (MacMediaProtocol.action(request.key())) {
            "PLAY" -> MacPlayback.PLAYING
            "PAUSE" -> MacPlayback.PAUSED
            else -> null
        }
        val data = mediaData(report)
        return if (expected == report.playback) RemoteReply.verified(data)
        else RemoteReply("SENT", "DISPATCHED", data)
    }

    private suspend fun registeredDevice(id: Long) = devices.getDeviceById(id)?.takeUnless { it.isDemoMode }

    private suspend fun dimmer(device: TvDeviceEntity): TvAppEntity? =
        if (device.deviceType != DeviceType.ANDROID_TV) null
        else apps.getAppsForDevice(device.id).first().firstOrNull { isDimmer(it) && dimmerPackage.matches(it.appIdentifier.trim()) }

    private fun isDimmer(app: TvAppEntity): Boolean =
        app.appIdentifier == AndroidAdbBrightness.DIMMER_PACKAGE ||
            app.appName.filter(Char::isLetterOrDigit).startsWith("tvdimmer", ignoreCase = true)

    private fun connectionError(ready: Readiness) = RemoteReply.rejected(
        if (ready == Readiness.AUTHORIZATION_REQUIRED) "DEVICE_AUTHORIZATION_REQUIRED" else "DEVICE_UNREACHABLE"
    )

    private fun transmission(sent: Boolean) =
        if (sent) RemoteReply.sent else RemoteReply.uncertain("TRANSMISSION_NOT_CONFIRMED")

    private fun deviceSummary(device: TvDeviceEntity) = JSONObject().put("deviceId", device.id)
        .put("name", device.name.take(256)).put("room", device.room.take(256)).put("type", device.deviceType.name)

    private fun timerData(timer: SleepTimerState) = JSONObject()
        .put("remainingSeconds", timer.remainingSeconds()).put("minutes", timer.minutes)

    private fun mediaData(source: MacMediaSource) = JSONObject().put("sourceId", source.id)
        .put("service", source.service.name)
        .apply {
            if (source.verified && source.playback != MacPlayback.UNKNOWN) put("playback", source.playback.name)
            if (source.verified) put("actions", JSONArray(source.capabilities.filter { it in MEDIA_ACTIONS }.distinct()))
        }

    private fun <T> page(name: String, rows: List<T>, id: (T) -> Long, data: (T) -> JSONObject): RemoteReply =
        RemoteReply.verified(JSONObject().put(name, JSONArray(rows.take(PAGE_SIZE).map(data)))
            .put("nextAfterId", if (rows.size > PAGE_SIZE) id(rows[PAGE_SIZE - 1]) else JSONObject.NULL))

    private companion object {
        const val PAGE_SIZE = 32
        val MEDIA_ACTIONS = setOf("PLAY", "PAUSE", "PLAY_PAUSE", "NEXT", "PREVIOUS")
        val MEDIA_ISSUES = setOf("CHROME_JAVASCRIPT", "AUTOMATION_DENIED", "PLAYER_ERROR")
    }
}

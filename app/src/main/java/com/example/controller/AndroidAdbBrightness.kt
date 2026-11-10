package com.example.controller

import com.example.data.local.AdbSettingsStore
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AdbSetupState(
    val device: TvDeviceEntity,
    val packageName: String,
    val port: Int = 5555,
    val busy: Boolean = false,
    val error: String? = null
)

/** Owns one requested launch, including initial setup, retries and cancellation. */
class AndroidAdbBrightness(
    private val scope: CoroutineScope,
    private val settings: AdbSettingsStore,
    private val launchApp: suspend (String, Int, String) -> Unit,
    private val onResult: (TvDeviceEntity, String, Boolean) -> Unit
) {
    private val _setup = MutableStateFlow<AdbSetupState?>(null)
    val setup = _setup.asStateFlow()
    private var job: Job? = null
    private var targetId: Long? = null
    private var generation = 0L

    fun open(device: TvDeviceEntity, packageName: String) {
        if (device.deviceType != DeviceType.ANDROID_TV || job?.isActive == true) return
        if (_setup.value != null) return
        val port = settings.port(device.id)
        if (port == null) configure(device, packageName)
        else start(AdbSetupState(device, packageName, port))
    }

    fun configure(device: TvDeviceEntity, packageName: String = DIMMER_PACKAGE) {
        if (device.deviceType != DeviceType.ANDROID_TV) return
        cancel()
        targetId = device.id
        _setup.value = AdbSetupState(device, packageName, settings.port(device.id) ?: 5555)
    }

    fun submit(portText: String) {
        val state = _setup.value ?: return
        if (job?.isActive == true) return
        val port = portText.trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            _setup.value = state.copy(error = "Ingresa un puerto entre 1 y 65535.")
            return
        }
        start(state.copy(port = port))
    }

    private fun start(state: AdbSetupState) {
        val expected = ++generation
        targetId = state.device.id
        // For an already configured TV no dialog is shown unless the launch fails.
        if (_setup.value != null) _setup.value = state.copy(busy = true, error = null)
        job = scope.launch {
            try {
                launchApp(state.device.ipAddress, state.port, state.packageName)
                currentCoroutineContext().ensureActive()
                if (generation != expected) return@launch
                settings.save(state.device.id, state.port)
                _setup.value = null
                onResult(state.device, "Abrir TVDimmer por ADB", true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation != expected) return@launch
                val message = error.message ?: "No se pudo abrir TVDimmer por ADB."
                _setup.value = state.copy(busy = false, error = message)
                onResult(state.device, message, false)
            }
        }
    }

    fun cancel() {
        generation++
        job?.cancel(); job = null
        targetId = null
        _setup.value = null
    }

    fun forget(deviceId: Long) {
        if (targetId == deviceId) cancel()
        settings.clear(deviceId)
    }

    companion object { const val DIMMER_PACKAGE = "com.tribalapps.tvdimmer" }
}

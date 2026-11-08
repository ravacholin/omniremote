package com.example.share

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.example.controller.RemoteSession
import com.example.data.local.AppDatabase
import com.example.data.model.DeviceType
import com.example.data.model.MacLinkEntity
import com.example.network.MacResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

/** Screen state; the session owns the queue's dispatch lock across Activity instances. */
data class MacShareRequest(
    val id: String,
    val link: SharedMacLink?,
    val savedEntries: Map<Long, Long> = emptyMap()
)

@OptIn(ExperimentalCoroutinesApi::class)
class MacLinksState(context: Context, private val session: RemoteSession, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val database = AppDatabase.getInstance(context)
    private val dao = database.macLinkDao()
    private val queue = session.macLinks
    private var initialized = false
    private val _pendingShare = MutableStateFlow<MacShareRequest?>(null)
    val pendingShare = _pendingShare.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _queueDeviceId = MutableStateFlow<Long?>(null)
    val queueDeviceId = _queueDeviceId.asStateFlow()
    val queueLinks = _queueDeviceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.observe(id)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun observeLinks(deviceId: Long): Flow<List<MacLinkEntity>> = dao.observe(deviceId)

    fun receive(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        initialized = true
        _queueDeviceId.value = null
        val link = try { SharedMacLink.fromIntent(intent) } catch (_: RuntimeException) { null }
        _error.value = null
        _pendingShare.value = MacShareRequest(UUID.randomUUID().toString(), link)
    }

    fun dismissShare() { if (!_busy.value) { _pendingShare.value = null; _error.value = null } }
    fun showQueue(deviceId: Long) { _error.value = null; _queueDeviceId.value = deviceId }
    fun closeQueue() { _queueDeviceId.value = null; _error.value = null }

    fun submit(deviceId: Long, open: Boolean) {
        val request = _pendingShare.value ?: return
        val link = request.link ?: return
        if (_busy.value) return
        val generation = session.targetGeneration
        _busy.value = true
        _error.value = null
        scope.launch {
            try {
                val device = database.tvDeviceDao().getDeviceById(deviceId)
                if (device?.deviceType != DeviceType.MACOS) {
                    if (_pendingShare.value?.id == request.id) _error.value = "Elegí un Mac guardado como destino."
                    return@launch
                }
                val oldId = request.savedEntries[deviceId]?.takeIf { dao.get(deviceId, it) != null }
                val id = oldId ?: queue.enqueue(deviceId, link)
                if (_pendingShare.value?.id == request.id) {
                    _pendingShare.value = request.copy(savedEntries = request.savedEntries + (deviceId to id))
                }
                val result = if (open) queue.open(deviceId, id) { target, url ->
                    if (generation != session.targetGeneration) MacResult.Failed("Cambió el dispositivo. El enlace sigue guardado.")
                    else session.controller.openMacLink(target, url)
                } else MacResult.Ok("")
                if (_pendingShare.value?.id == request.id) {
                    when (result) {
                        is MacResult.Ok -> {
                            _pendingShare.value = null
                            Toast.makeText(context, if (open) "Abierto en ${device.name}" else "Guardado para después en ${device.name}", Toast.LENGTH_SHORT).show()
                        }
                        is MacResult.Failed -> _error.value = "${result.message} El enlace queda en la cola."
                        is MacResult.LoginNeeded -> _error.value = result.message
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (_pendingShare.value?.id == request.id) _error.value = "No se pudo guardar el enlace. Comprobá que el Mac siga agregado e intentá de nuevo."
            } finally { _busy.value = false }
        }
    }

    fun openNext(deviceId: Long) {
        if (_busy.value || _queueDeviceId.value != deviceId) return
        val generation = session.targetGeneration
        _busy.value = true
        _error.value = null
        scope.launch {
            try {
                val result = queue.openNext(deviceId) { target, url ->
                    if (generation != session.targetGeneration) MacResult.Failed("Cambió el dispositivo.")
                    else session.controller.openMacLink(target, url)
                }
                if (_queueDeviceId.value == deviceId) _error.value = when (result) {
                    is MacResult.Ok -> null
                    is MacResult.Failed -> result.message
                    is MacResult.LoginNeeded -> result.message
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (_queueDeviceId.value == deviceId) _error.value = "No se pudo abrir. El enlace sigue guardado." }
            finally { _busy.value = false }
        }
    }

    fun remove(deviceId: Long, id: Long) {
        if (_busy.value || _queueDeviceId.value != deviceId) return
        scope.launch {
            try { dao.delete(deviceId, id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _error.value = "No se pudo eliminar el enlace." }
        }
    }

    fun onDeviceDeleted(deviceId: Long) { if (_queueDeviceId.value == deviceId) closeQueue() }

    fun saveState(bundle: Bundle) {
        bundle.remove("macShareId")
        val request = _pendingShare.value ?: return
        bundle.putString("macShareId", request.id)
        bundle.putString("macShareUrl", request.link?.url)
        bundle.putString("macShareTitle", request.link?.title)
        bundle.putLongArray("macShareDevices", request.savedEntries.keys.toLongArray())
        bundle.putLongArray("macShareEntries", request.savedEntries.values.toLongArray())
    }

    fun restoreState(bundle: Bundle) {
        if (initialized) return
        initialized = true
        val id = bundle.getString("macShareId") ?: return
        val devices = (bundle.getLongArray("macShareDevices") ?: longArrayOf())
        val entries = (bundle.getLongArray("macShareEntries") ?: longArrayOf())
        _pendingShare.value = MacShareRequest(id, SharedMacLink.parse(bundle.getString("macShareUrl").orEmpty(),
            bundle.getString("macShareTitle")), devices.zip(entries).toMap())
    }
}

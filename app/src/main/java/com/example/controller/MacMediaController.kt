package com.example.controller

import com.example.data.model.RemoteKey
import com.example.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Shared by the screen and mini remote. IDs are only meaningful within this session. */
data class MacMediaState(
    val sources: List<MacMediaSource> = emptyList(),
    val selectedId: String? = null,
    val revision: Long = 0,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val issues: List<MacMediaIssue> = emptyList(),
    val message: String? = null
) {
    val selectedSource: MacMediaSource? get() = sources.find { it.id == selectedId }
    val label: String get() = if (selectedId == null) "Automático" else selectedSource?.label ?: "Fuente no disponible"
    fun canSend(key: RemoteKey): Boolean = !busy && (selectedId == null || selectedSource?.supports(key) == true)
}

class MacMediaController(
    private val scope: CoroutineScope,
    private val runner: suspend (MacScript, () -> Boolean) -> MacResult
) {
    private val _state = MutableStateFlow(MacMediaState())
    val state = _state.asStateFlow()
    private val epoch = AtomicLong()
    private val scan = AtomicLong()
    private var discovery: Job? = null
    private var action: Job? = null
    private var chromeProcessId: String? = null

    fun reset() {
        invalidate()
        chromeProcessId = null
        _state.value = MacMediaState(revision = _state.value.revision)
    }

    fun invalidate() {
        epoch.incrementAndGet()
        scan.incrementAndGet()
        discovery?.cancel(); action?.cancel()
        val old = _state.value
        _state.value = old.copy(loading = false, busy = false, revision = old.revision + 1,
            sources = old.sources.map { it.copy(verified = false) })
    }

    fun select(id: String?) {
        if (id != null && _state.value.sources.none { it.id == id }) return
        if (id == _state.value.selectedId) return
        action?.cancel()
        _state.value = _state.value.copy(selectedId = id, revision = _state.value.revision + 1, busy = false, message = null)
    }

    fun refresh() {
        if (_state.value.busy) return
        discovery?.cancel()
        val expected = epoch.get()
        val scanId = scan.incrementAndGet()
        _state.value = _state.value.copy(loading = true, message = null)
        discovery = scope.launch {
            val job = currentCoroutineContext().job
            val valid = { job.isActive && epoch.get() == expected && scan.get() == scanId }
            try {
                val reply = read(MacMediaScripts.discover, valid)
                if (!valid()) return@launch
                if (reply.error != null) {
                    _state.value = _state.value.copy(message = reply.error.message)
                    return@launch
                }
                val pid = reply.chromeProcessId
                if (pid != null && chromeProcessId != null && pid != chromeProcessId && _state.value.selectedSource?.service?.isWeb == true) select(null)
                if (pid != null) chromeProcessId = pid
                val pinned = _state.value.selectedSource
                val candidates = reply.sources.toMutableList()
                if (pinned != null && candidates.none { it.id == pinned.id }) {
                    candidates += unavailable(pinned, "La fuente elegida ya no está disponible. Elegí de nuevo si cambió de servicio.")
                    if (pinned.verified || pinned.issue?.code != "SOURCE_GONE") {
                        _state.value = _state.value.copy(revision = _state.value.revision + 1)
                    }
                }
                _state.value = _state.value.copy(sources = candidates, issues = reply.issues)
                for (candidate in reply.sources) {
                    yield() // Release the SSH command lock between candidate inspections.
                    val result = read(MacMediaScripts.inspect(candidate), valid)
                    if (!valid()) return@launch
                    val observed = result.source?.takeIf { it.id == candidate.id && it.service == candidate.service }
                    if (observed != null) replace(observed)
                    else if (result.error?.code == "NO_MEDIA" && candidate.id != _state.value.selectedId) {
                        _state.value = _state.value.copy(sources = _state.value.sources.filterNot { it.id == candidate.id })
                    } else replace(candidate.copy(verified = false, issue = result.error ?: MacMediaIssue("SOURCE_GONE", "Fuente no disponible")))
                }
                _state.value = _state.value.copy(sources = _state.value.sources.sortedWith(
                    compareBy<MacMediaSource> { if (!it.verified) 2 else if (it.playback == MacPlayback.PLAYING) 0 else 1 }
                        .thenBy { it.label }.thenBy { it.id }))
            } finally {
                if (valid()) _state.value = _state.value.copy(loading = false)
            }
        }
    }

    /** Called on reconnect/foreground only; it does not enumerate unrelated tabs. */
    fun refreshSelected() {
        if (_state.value.selectedId == null) return
        val source = _state.value.selectedSource ?: return
        val expected = epoch.get()
        val revision = _state.value.revision
        action?.cancel()
        action = scope.launch {
            val job = currentCoroutineContext().job
            val valid = { job.isActive && epoch.get() == expected && _state.value.revision == revision }
            val reply = read(MacMediaScripts.inspect(source), valid)
            if (valid()) applyReply(source, reply)
        }
    }

    /** Returns true for any media key, including a blocked manual target. Never falls back. */
    fun send(key: RemoteKey): Boolean {
        if (MacMediaProtocol.action(key) == null) return false
        val captured = _state.value
        if (!captured.canSend(key)) {
            _state.value = captured.copy(message = captured.selectedSource?.issue?.message ?: "Acción no disponible. Actualizá o elegí otra fuente.")
            return true
        }
        discovery?.cancel(); scan.incrementAndGet()
        val expected = epoch.get()
        val revision = captured.revision
        val source = captured.selectedSource
        _state.value = captured.copy(busy = true, loading = false, message = null)
        action = scope.launch {
            val job = currentCoroutineContext().job
            val valid = { job.isActive && epoch.get() == expected && _state.value.revision == revision }
            try {
                if (captured.selectedId == null) {
                    val result = runner(requireNotNull(MacScripts.forKey(key)), valid)
                    if (valid()) _state.value = _state.value.copy(message = when (result) {
                        is MacResult.Ok -> if (result.output == MacScripts.NO_PLAYER) "No hay un reproductor disponible." else "Orden enviada en modo automático."
                        is MacResult.Failed -> result.message
                        is MacResult.LoginNeeded -> result.message
                    })
                    return@launch
                }
                if (source == null) return@launch
                val before = read(MacMediaScripts.inspect(source), valid)
                if (!valid()) return@launch
                val fresh = before.source?.takeIf { it.id == source.id && it.service == source.service }
                if (fresh == null || before.error != null) { applyReply(source, before); return@launch }
                replace(fresh)
                if (fresh.token != source.token) {
                    _state.value = _state.value.copy(message = "Cambió el contenido. Fuente actualizada; volvé a pulsar.")
                    return@launch
                }
                if (!fresh.supports(key)) { _state.value = _state.value.copy(message = "Acción no disponible en este reproductor."); return@launch }
                val response = read(MacMediaScripts.command(fresh, key), valid)
                if (!valid()) return@launch
                applyReply(fresh, response)
                if (response.error != null) return@launch
                _state.value = _state.value.copy(message = "Orden enviada. El estado se confirma al consultar el reproductor.")
                delay(150)
                val observed = read(MacMediaScripts.inspect(fresh), valid)
                if (valid()) applyReply(fresh, observed)
            } finally {
                if (valid()) _state.value = _state.value.copy(busy = false)
            }
        }
        return true
    }

    private fun unavailable(source: MacMediaSource, message: String) = source.copy(verified = false,
        issue = MacMediaIssue("SOURCE_GONE", message))

    private fun replace(source: MacMediaSource) {
        val old = _state.value
        _state.value = old.copy(sources = old.sources.map { if (it.id == source.id) source else it })
    }

    private fun applyReply(source: MacMediaSource, reply: MacMediaReply) {
        if (reply.error?.code == "CHROME_RESTARTED" && source.id == _state.value.selectedId) {
            select(null)
            _state.value = _state.value.copy(message = "Chrome se reinició. Modo automático; elegí de nuevo la pestaña.")
            return
        }
        val fresh = reply.source?.takeIf { it.id == source.id && it.service == source.service }
        if (fresh != null) replace(fresh)
        else replace(source.copy(verified = false, issue = reply.error ?: MacMediaIssue("SOURCE_GONE", "Fuente no disponible")))
        if (reply.error != null) _state.value = _state.value.copy(message = reply.error.message)
    }

    private suspend fun read(script: MacScript, valid: () -> Boolean): MacMediaReply {
        if (!valid()) throw CancellationException()
        return when (val result = runner(script, valid)) {
            is MacResult.Ok -> MacMediaProtocol.parse(result.output).let { reply ->
                reply.copy(source = reply.source?.copy(observedAtMillis = System.currentTimeMillis()))
            }
            is MacResult.Failed -> MacMediaReply(error = MacMediaIssue("TRANSPORT", result.message))
            is MacResult.LoginNeeded -> MacMediaReply(error = MacMediaIssue("LOGIN_NEEDED", result.message))
        }
    }
}

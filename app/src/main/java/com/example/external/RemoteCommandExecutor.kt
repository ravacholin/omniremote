package com.example.external

import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Persists a claim before running a side effect. A process restart never replays a claim. */
internal class RemoteCommandExecutor(
    private val scope: CoroutineScope,
    private val ledger: SharedPreferences,
    private val callerNamespace: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val dispatch: suspend (RemoteRequest, () -> Boolean) -> RemoteReply
) {
    private val active = mutableMapOf<String, Long>()

    @Synchronized
    fun execute(id: String, request: RemoteRequest, authorized: () -> Boolean): String {
        val key = callerNamespace + id
        val fingerprint = request.fingerprint()
        val existing = read(key)
        if (existing != null) {
            if (existing.getString("fingerprint") != fingerprint) return reject(id, "EXECUTION_ID_CONFLICT")
            return status(key, id, existing)
        }
        if (request.deviceId in active.values) return reject(id, "DEVICE_BUSY")
        if (active.size >= MAX_ACTIVE) return reject(id, "SERVICE_BUSY")
        val editor = ledger.edit()
        val cutoff = now() - RETENTION_MS
        var retained = 0
        for ((savedKey, encoded) in ledger.all) {
            val created = JSONObject(encoded as String).getLong("created")
            if (savedKey !in active && created <= cutoff) editor.remove(savedKey) else retained++
        }
        if (retained >= MAX_RECORDS) return reject(id, "LEDGER_FULL")
        val record = JSONObject().put("created", now()).put("fingerprint", fingerprint)
        if (!editor.putString(key, record.toString()).commit()) return reject(id, "STORAGE_UNAVAILABLE")
        active[key] = request.deviceId
        val deadline = elapsed() + TIMEOUT_MS
        scope.launch {
            var result = RemoteReply.uncertain("INTERRUPTED")
            try {
                result = withTimeoutOrNull((deadline - elapsed()).coerceAtLeast(0)) {
                    val job = currentCoroutineContext()
                    if (!authorized()) RemoteReply.rejected("CALLER_NO_LONGER_AUTHORIZED")
                    else dispatch(request) { job.isActive && elapsed() < deadline }
                } ?: RemoteReply.uncertain("TIMEOUT")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                result = RemoteReply.uncertain("EXECUTION_FAILED")
            } finally {
                finish(key, record, result.encode(id))
            }
        }
        return RemoteReply.pending.encode(id)
    }

    @Synchronized
    fun getExecution(id: String): String {
        val key = callerNamespace + id
        val record = read(key) ?: return reject(id, "UNKNOWN_EXECUTION")
        return status(key, id, record)
    }

    @Synchronized
    private fun finish(key: String, record: JSONObject, reply: String) {
        // If this write fails, the durable claim still prevents replay.
        try {
            ledger.edit().putString(key, record.put("reply", reply).toString()).commit()
        } finally {
            active.remove(key)
        }
    }

    private fun read(key: String): JSONObject? {
        val record = ledger.getString(key, null)?.let(::JSONObject) ?: return null
        return record.takeIf { key in active || now() - it.getLong("created") < RETENTION_MS }
    }

    private fun status(key: String, id: String, record: JSONObject): String = when {
        record.has("reply") -> record.getString("reply")
        key in active -> RemoteReply.pending.encode(id)
        else -> RemoteReply.uncertain("INTERRUPTED").encode(id)
    }

    private fun reject(id: String, code: String) = RemoteReply.rejected(code).encode(id)

    companion object {
        const val TIMEOUT_MS = 35_000L
        private const val RETENTION_MS = 24 * 60 * 60 * 1000L
        private const val MAX_RECORDS = 2048
        private const val MAX_ACTIVE = 8
        val executionIdPattern = Regex("[A-Za-z0-9_-]{12,64}")
    }
}

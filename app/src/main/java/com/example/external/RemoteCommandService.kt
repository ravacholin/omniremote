package com.example.external

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.example.BuildConfig
import com.example.controller.RemoteSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Exported Binder endpoint. The system delivers onBind; authenticate the caller at each IPC. */
class RemoteCommandService : Service() {
    private val authorization by lazy {
        InstoAuthorization(packageManager, BuildConfig.INSTO_PACKAGE, BuildConfig.INSTO_CERTIFICATE_SHA256)
    }

    private val runtime by lazy { RemoteCommandRuntime.get(applicationContext) }

    private val binder = object : IRemoteCommandService.Stub() {
        override fun getApiVersion(): Int {
            enforceCaller()
            return API_VERSION
        }

        override fun listDevices(apiVersion: Int, afterDeviceId: Long): String =
            query(apiVersion, afterDeviceId >= 0) { runtime.targets.listDevices(afterDeviceId) }

        override fun getDevice(apiVersion: Int, deviceId: Long): String =
            query(apiVersion, deviceId > 0) { runtime.targets.getDevice(deviceId) }

        override fun listApps(apiVersion: Int, deviceId: Long, afterAppId: Long): String =
            query(apiVersion, deviceId > 0 && afterAppId >= 0) { runtime.targets.listApps(deviceId, afterAppId) }

        override fun execute(apiVersion: Int, executionId: String?, deviceId: Long,
            command: String?, argumentsJson: String?): String {
            val uid = enforceCaller()
            checkVersion(apiVersion)?.let { return it }
            val id = executionId.validExecutionId() ?: return RemoteReply.rejected("INVALID_EXECUTION_ID").encode()
            if (command == null || Command.entries.none { it.name == command }) {
                return RemoteReply("UNSUPPORTED", "UNKNOWN_COMMAND").encode(id)
            }
            val request = try {
                RemoteRequest.parse(apiVersion, deviceId, command, argumentsJson)
            } catch (_: IllegalArgumentException) {
                return RemoteReply.rejected("INVALID_ARGUMENTS").encode(id)
            }
            return withServiceIdentity {
                try {
                    runtime.executor.execute(id, request) { authorization.allows(uid) }
                } catch (_: Exception) {
                    RemoteReply.rejected("STORAGE_UNAVAILABLE").encode(id)
                }
            }
        }

        override fun getExecution(apiVersion: Int, executionId: String?): String {
            enforceCaller()
            checkVersion(apiVersion)?.let { return it }
            val id = executionId.validExecutionId() ?: return RemoteReply.rejected("INVALID_EXECUTION_ID").encode()
            return withServiceIdentity {
                try {
                    runtime.executor.getExecution(id)
                } catch (_: Exception) {
                    RemoteReply.uncertain("STORAGE_UNAVAILABLE").encode(id)
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = binder.takeIf {
        intent.action == BIND_ACTION && intent.component?.packageName == packageName &&
            intent.component?.className == RemoteCommandService::class.java.name
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun enforceCaller(): Int {
        val uid = Binder.getCallingUid()
        if (!authorization.allows(uid)) throw SecurityException("OmniRemote external control access denied")
        return uid
    }

    private fun query(version: Int, valid: Boolean, operation: suspend () -> RemoteReply): String {
        enforceCaller()
        checkVersion(version)?.let { return it }
        if (!valid) return RemoteReply.rejected("INVALID_ARGUMENTS").encode()
        return withServiceIdentity {
            try {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(3_000) { operation() } ?: RemoteReply.rejected("QUERY_TIMEOUT")
                }.encode()
            } catch (_: Exception) {
                RemoteReply.rejected("QUERY_UNAVAILABLE").encode()
            }
        }
    }

    private inline fun <T> withServiceIdentity(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        return try {
            block()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun checkVersion(version: Int): String? =
        if (version == API_VERSION) null else RemoteReply.rejected("UNSUPPORTED_VERSION").encode()

    private fun String?.validExecutionId(): String? =
        this?.takeIf { it.length in 12..64 && RemoteCommandExecutor.executionIdPattern.matches(it) }

    companion object {
        const val BIND_ACTION = "com.aistudio.omnitvremote.qvrtm.BIND_EXTERNAL_CONTROL"
    }
}

/** Keep the same executor across binds/unbinds within the app process. */
private class RemoteCommandRuntime(context: Context) {
    val targets = RemoteTargets(context, RemoteSession.getInstance(context))
    val executor = RemoteCommandExecutor(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ledger = context.getSharedPreferences("external_executions", Context.MODE_PRIVATE),
        callerNamespace = digest("${BuildConfig.INSTO_PACKAGE}:${BuildConfig.INSTO_CERTIFICATE_SHA256}".toByteArray()),
        dispatch = targets::dispatch
    )

    companion object {
        private var instance: RemoteCommandRuntime? = null

        @Synchronized
        fun get(context: Context): RemoteCommandRuntime =
            instance ?: RemoteCommandRuntime(context).also { instance = it }
    }
}

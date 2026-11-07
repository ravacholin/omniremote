package com.example.network

import com.example.data.model.ConnectionState
import com.example.data.model.TvDeviceEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

enum class DiagnosticStatus(val label: String) {
    PASSED("Comprobado"), ATTENTION("Requiere atención"), UNKNOWN("No comprobado")
}

enum class DiagnosticAction(val label: String) {
    NETWORK_SETTINGS("Abrir ajustes de red"), USE_NEW_ADDRESS("Actualizar IP y conectar"),
    RECONNECT("Reintentar conexión"), AUTHORIZE("Revisar autorización"),
    MANAGE_DEVICE("Revisar equipo e IP"), WAKE("Intentar encender")
}

data class DiagnosticCheck(val title: String, val status: DiagnosticStatus, val message: String)
data class ConnectionDiagnosticReport(
    val deviceId: Long,
    val originalAddress: String,
    val checks: List<DiagnosticCheck>,
    val actions: List<DiagnosticAction>,
    val newAddress: String? = null
)

data class AuthorizationEvidence(
    val state: ConnectionState,
    val hasSavedCredential: Boolean,
    val samsungEvent: String? = null
)

/** Read-only operations: a diagnosis cannot replace identities or request pairing. */
interface DiagnosticEnvironment {
    fun localIp(): String?
    suspend fun responds(device: TvDeviceEntity): Boolean
    suspend fun hardwareAddress(device: TvDeviceEntity): String?
    suspend fun discover(): List<DiscoveredTv>
    fun authorization(device: TvDeviceEntity): AuthorizationEvidence
}

class ConnectionDiagnosticRunner(private val environment: DiagnosticEnvironment) {
    suspend fun run(device: TvDeviceEntity, onStage: (String) -> Unit = {}): ConnectionDiagnosticReport {
        val checks = mutableListOf<DiagnosticCheck>()
        val actions = linkedSetOf<DiagnosticAction>()
        onStage("Comprobando la red local…")
        val localIp = environment.localIp()
        if (localIp == null) {
            return ConnectionDiagnosticReport(device.id, device.ipAddress, listOf(
                DiagnosticCheck("Red local", DiagnosticStatus.ATTENTION,
                    "No encontramos una red Wi-Fi o Ethernet local. Conectá el teléfono a la misma red que el equipo; no hace falta internet."),
                DiagnosticCheck("Equipo e IP", DiagnosticStatus.UNKNOWN, "Sin red local no pudimos buscar el equipo."),
                DiagnosticCheck("Autorización", DiagnosticStatus.UNKNOWN, "No se pudo comprobar el permiso. La vinculación guardada se conserva.")
            ), listOf(DiagnosticAction.NETWORK_SETTINGS))
        }
        checks += DiagnosticCheck("Red local", DiagnosticStatus.PASSED,
            "El teléfono tiene una dirección local ($localIp). Esto no confirma que el router permita comunicar los equipos.")

        onStage("Comprobando el equipo y su dirección…")
        val connected = environment.authorization(device).state is ConnectionState.Connected
        val reachable = connected || environment.responds(device)
        val savedMac = WakeOnLan.formatMacAddress(device.macAddress)
        val observedMac = if (reachable && !connected) environment.hardwareAddress(device)?.let(WakeOnLan::formatMacAddress) else null
        val identityConflict = savedMac != null && observedMac != null && savedMac != observedMac
        var identityConfirmed = connected || (savedMac != null && savedMac == observedMac)
        var newAddress: String? = null
        if (reachable && !identityConflict && (savedMac == null || identityConfirmed)) {
            checks += DiagnosticCheck("Equipo e IP", DiagnosticStatus.PASSED,
                if (connected) "Hay una sesión activa con ${device.ipAddress}."
                else "Un servicio del equipo responde en ${device.ipAddress}. " +
                    if (savedMac != null && savedMac == observedMac) "La identidad del equipo coincide."
                    else "No pudimos confirmar su identidad por hardware; la respuesta no demuestra autorización.")
        } else {
            onStage("Buscando si el equipo cambió de IP…")
            var searchFailed = false
            val discovered = try {
                withTimeoutOrNull(30_000) { environment.discover() }.also { if (it == null) searchFailed = true }.orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                searchFailed = true
                emptyList()
            }
            currentCoroutineContext().ensureActive()
            val matches = discovered.filter {
                savedMac != null && it.deviceType == device.deviceType &&
                    WakeOnLan.formatMacAddress(it.macAddress) == savedMac
            }.distinctBy { it.ipAddress }
            newAddress = matches.singleOrNull()?.ipAddress?.takeIf { it != device.ipAddress && !searchFailed }
            if (!searchFailed && reachable && !identityConflict && matches.singleOrNull()?.ipAddress == device.ipAddress) {
                identityConfirmed = true
            }
            val explanation = when {
                newAddress != null -> "Encontramos la misma identidad de equipo en $newAddress. Podés actualizar la IP conservando la vinculación."
                identityConfirmed -> "La búsqueda confirmó la identidad del equipo en la dirección guardada."
                searchFailed -> "La búsqueda no pudo completarse. No podemos determinar si cambió la IP."
                matches.size > 1 -> "Más de una dirección anuncia la misma identidad. Revisá la IP del equipo antes de cambiarla."
                savedMac == null -> "No hay una identidad de hardware guardada para reconocerlo en otra IP. Revisá la dirección en el equipo."
                else -> "No encontramos una nueva dirección que coincida con la identidad guardada. Puede estar apagado o aislado por el router."
            }
            checks += DiagnosticCheck("Equipo e IP", if (identityConfirmed) DiagnosticStatus.PASSED else DiagnosticStatus.ATTENTION,
                (when {
                    identityConflict -> "La IP guardada responde con otra identidad. "
                    reachable -> "Un servicio responde en ${device.ipAddress}, pero su respuesta no confirmó la identidad. "
                    else -> "El servicio no respondió en ${device.ipAddress}. "
                }) + explanation)
            if (newAddress != null) actions += DiagnosticAction.USE_NEW_ADDRESS else if (!identityConfirmed) {
                actions += DiagnosticAction.MANAGE_DEVICE
                if (!reachable && !identityConflict) actions += DiagnosticAction.WAKE
            }
        }

        onStage("Revisando la autorización y los registros…")
        val evidence = environment.authorization(device)
        val authorization = when {
            newAddress != null || identityConflict || (savedMac != null && !identityConfirmed) || !reachable -> DiagnosticCheck("Autorización", DiagnosticStatus.UNKNOWN,
                "No se pudo comprobar con el equipo guardado. Conservamos sus credenciales para el próximo intento.")
            evidence.state is ConnectionState.Connected -> DiagnosticCheck("Autorización", DiagnosticStatus.PASSED,
                "El equipo aceptó la sesión de control actual.")
            evidence.state is ConnectionState.WaitingAuth -> {
                actions += DiagnosticAction.AUTHORIZE
                DiagnosticCheck("Autorización", DiagnosticStatus.ATTENTION,
                    "La conexión está esperando autorización. Revisá el permiso o el código en el equipo; no hace falta eliminarlo.")
            }
            else -> {
                // A stored token or an old successful handshake is not a current authorization.
                if (evidence.state !is ConnectionState.Connecting) actions += DiagnosticAction.RECONNECT
                val history = when (evidence.samsungEvent) {
                    "authorization_rejected" -> "En el último intento registrado, el equipo rechazó el permiso. Revisá que OmniRemote esté permitido en la TV. "
                    "tv_authorization_timeout" -> "En el último intento registrado se agotó la espera del permiso. Revisá la pantalla de la TV. "
                    "authorization_confirmed" -> "Los registros muestran una autorización anterior, pero no una sesión activa ahora. "
                    else -> ""
                }
                DiagnosticCheck("Autorización", DiagnosticStatus.UNKNOWN, history + when {
                    evidence.state is ConnectionState.Connecting -> "La conexión sigue en curso. Esperá a que termine y volvé a comprobar."
                    evidence.hasSavedCredential -> "Hay credenciales guardadas. Reintentá la conexión para comprobar si el equipo todavía las acepta."
                    else -> "No encontramos credenciales guardadas. Intentá conectar para que el equipo indique si necesita autorización."
                })
            }
        }
        checks += authorization
        if (evidence.state is ConnectionState.Connecting || evidence.state is ConnectionState.WaitingAuth) {
            actions.removeAll(setOf(DiagnosticAction.RECONNECT, DiagnosticAction.WAKE, DiagnosticAction.USE_NEW_ADDRESS))
            checks += DiagnosticCheck("Conexión en curso", DiagnosticStatus.ATTENTION,
                "Hay un intento de conexión o un permiso pendiente. Esperá a que termine o revisá la pantalla del equipo antes de volver a comprobar.")
        }
        currentCoroutineContext().ensureActive()
        return ConnectionDiagnosticReport(device.id, device.ipAddress, checks, actions.toList(), newAddress)
    }
}

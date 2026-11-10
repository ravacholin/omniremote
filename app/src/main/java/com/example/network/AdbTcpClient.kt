package com.example.network

import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Classic authenticated adbd TCP, not Android 11+ wireless TLS/code pairing. */
class AdbTcpClient(private val identity: AdbIdentity, private val timeoutMs: Long = 25_000) {
    suspend fun launch(host: String, port: Int, packageName: String) {
        require(port in 1..65535) { "Puerto ADB inválido" }
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "Paquete Android inválido" }
        require(host.isNotBlank()) { "Dirección de TV vacía" }
        try {
            withTimeout(timeoutMs) {
                coroutineScope {
                    val socket = Socket()
                    // Cancellation must close a blocking read/connect, not wait for its SO_TIMEOUT.
                    val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { socket.close() }
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            socket.connect(InetSocketAddress(host, port), 5000)
                            socket.soTimeout = 20_000
                            session(socket, packageName)
                        }
                    } finally {
                        socket.close()
                        closer.cancelAndJoin()
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw AdbException("Se agotó el tiempo ADB. Activa depuración de red y autoriza OmniRemote en la TV.", error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: AdbException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw AdbException("No se pudo conectar por ADB. Revisa el puerto, activa depuración de red y autoriza OmniRemote en la TV.", error)
        }
    }

    private suspend fun session(socket: Socket, packageName: String) {
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        // Advertise the original version: checksums required, 4 KiB maximum, legacy shell.
        send(output, "CNXN", 0x01000000, MAX_PAYLOAD, "host::\u0000".toByteArray())
        var sentSignature = false
        var sentKey = false
        var count = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (++count > 8) throw AdbException("La TV rechazó la autorización ADB. Autoriza OmniRemote en pantalla.")
            val packet = read(input)
            when (packet.command) {
                "CNXN" -> {
                    if (packet.b < MAX_PAYLOAD || packet.a < 0x01000000) throw AdbException("Versión ADB incompatible")
                    break
                }
                "AUTH" -> {
                    if (packet.a != 1 || packet.body.size != 20) throw AdbException("Desafío de autorización ADB inválido")
                    if (!sentSignature) {
                        send(output, "AUTH", 2, 0, identity.signToken(packet.body)); sentSignature = true
                    } else if (!sentKey) {
                        send(output, "AUTH", 3, 0, identity.publicKeyPayload()); sentKey = true
                    } else throw AdbException("La TV rechazó ADB. Acepta la autorización de OmniRemote en pantalla.")
                }
                "STLS" -> throw AdbException("Ese puerto usa depuración inalámbrica por código. Habilita ADB por red TCP y usa su puerto (normalmente 5555).")
                else -> throw AdbException("El puerto no respondió como ADB. Revisa el puerto de depuración de red.")
            }
        }
        currentCoroutineContext().ensureActive()
        socket.soTimeout = 5000
        // Package is validated above. No user text or app-link URL enters the shell.
        val command = "am start -W -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -p $packageName; printf '\\n__OMNI_ADB_EXIT__:%s\\n' \"\$?\""
        send(output, "OPEN", 1, 0, ("shell:$command\u0000").toByteArray())
        var remoteId: Int? = null
        val response = ByteArrayOutputStream()
        var packets = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (++packets > 2048) throw AdbException("Respuesta ADB demasiado larga")
            val packet = read(input)
            if (packet.b != 1) throw AdbException("Identificador de conexión ADB inválido")
            when (packet.command) {
                "OKAY" -> {
                    if (remoteId != null || packet.a == 0) throw AdbException("Apertura de canal ADB inválida")
                    remoteId = packet.a
                }
                "WRTE" -> {
                    if (remoteId == null || remoteId != packet.a) throw AdbException("Canal de respuesta ADB inválido")
                    if (response.size() + packet.body.size > 65536) throw AdbException("Respuesta ADB demasiado larga")
                    response.write(packet.body)
                    send(output, "OKAY", 1, remoteId)
                }
                "CLSE" -> {
                    if (remoteId == null || remoteId != packet.a) throw AdbException("La TV no permitió abrir la app mediante ADB")
                    send(output, "CLSE", 1, remoteId)
                    val text = response.toString("UTF-8").replace("\r", "")
                    if (!Regex("(?m)^Status: ok\\s*$").containsMatchIn(text) ||
                        !text.trimEnd().endsWith("\n__OMNI_ADB_EXIT__:0") ||
                        Regex("(?im)^Error[: ]|Exception").containsMatchIn(text)) {
                        throw AdbException("ADB no pudo abrir la app. Comprueba que TVDimmer esté instalada y habilitada en la TV.")
                    }
                    return
                }
                else -> throw AdbException("Respuesta de canal ADB inesperada")
            }
        }
    }

    private data class Packet(val command: String, val a: Int, val b: Int, val body: ByteArray)

    private fun read(input: DataInputStream): Packet {
        val header = ByteArray(24).also(input::readFully)
        val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = data.int
        val a = data.int; val b = data.int; val length = data.int
        val checksum = data.int; val magic = data.int
        if (magic != command.inv() || length !in 0..MAX_PAYLOAD) throw AdbException("Paquete ADB inválido")
        val body = ByteArray(length).also(input::readFully)
        if (body.sumOf { it.toInt() and 255 } != checksum) throw AdbException("Respuesta ADB dañada")
        return Packet(String(header, 0, 4, Charsets.US_ASCII), a, b, body)
    }

    private fun send(out: OutputStream, command: String, a: Int, b: Int, body: ByteArray = byteArrayOf()) {
        val number = ByteBuffer.wrap(command.toByteArray(Charsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).int
        out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(number).putInt(a).putInt(b).putInt(body.size)
            .putInt(body.sumOf { it.toInt() and 255 }).putInt(number.inv()).array())
        out.write(body); out.flush()
    }

    companion object { private const val MAX_PAYLOAD = 4096 }
}

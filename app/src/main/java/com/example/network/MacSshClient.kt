package com.example.network

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Key-based login saved after the first password login, plus the Mac's pinned host key. */
data class MacLogin(
    val username: String,
    val privateKey: String,
    val publicKey: String,
    val hostKey: String
)

interface MacLoginStore {
    fun getMacLogin(deviceId: Long): MacLogin?
    fun saveMacLogin(deviceId: Long, login: MacLogin)
    fun clearMacLogin(deviceId: Long)
}

sealed interface MacResult {
    data class Ok(val output: String) : MacResult
    data class LoginNeeded(val message: String) : MacResult
    data class Failed(val message: String) : MacResult
}

/**
 * Controls a Mac through its built-in Remote Login (SSH) server: nothing is installed on the Mac.
 * The password is used once to authorize a key generated on the phone and is never stored.
 * Calls block, so run them off the main thread.
 */
class MacSshClient(private val store: MacLoginStore?, private val commandTimeoutMs: Int = 6000) {
    private data class Target(val deviceId: Long, val ip: String, val port: Int)

    private val lock = Any()
    // Never held across network I/O; deletion waits only for final credential publication.
    private val stateLock = Any()
    @Volatile private var session: Session? = null
    @Volatile private var target: Target? = null
    // Bumped by disconnect() so a login still in flight for the previous Mac is dropped.
    @Volatile private var generation = 0

    val isConnected: Boolean get() = session?.isConnected == true

    fun connect(deviceId: Long, ip: String, port: Int): MacResult {
        val requestedIn = generation
        return synchronized(lock) {
            synchronized(stateLock) {
                if (requestedIn != generation) return MacResult.Failed("Conexión cancelada.")
                closeSession()
                target = Target(deviceId, ip, port)
            }
            openWithSavedLogin()
        }
    }

    /** Does not wait for a command in flight; that command's session is closed under it. */
    fun disconnect() = synchronized(stateLock) {
        generation++
        target = null
        closeSession()
    }

    /** First-time login: authorizes a new key on the Mac, then keeps the session open. */
    fun login(deviceId: Long, ip: String, port: Int, username: String, password: String,
        canSend: () -> Boolean = { true }): MacResult {
        val startedIn = generation
        return synchronized(lock) {
            val allowed = { startedIn == generation && canSend() && !Thread.currentThread().isInterrupted }
            synchronized(stateLock) {
                if (!allowed()) return MacResult.Failed("Conexión cancelada.")
                closeSession()
                target = Target(deviceId, ip, port)
            }
            val hostKeys = PinnedHostKey(expected = null)
            var provisional: Session? = null
            var retained = false
            try {
                val user = username.trim()
                val opened = JSch().apply { hostKeyRepository = hostKeys }.getSession(user, ip, port)
                provisional = opened
                opened.apply {
                    setPassword(password)
                    userInfo = PasswordUserInfo(password)
                    setConfig("PreferredAuthentications", "keyboard-interactive,password")
                    configure()
                    connect(CONNECT_TIMEOUT_MS)
                }
                if (!allowed()) return MacResult.Failed("Conexión cancelada.")
                val generated = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
                val (privateKey, publicKey) = try {
                    ByteArrayOutputStream().also { generated.writePrivateKey(it) }.toString(Charsets.UTF_8.name()) to
                        ByteArrayOutputStream().also { generated.writePublicKey(it, KEY_COMMENT) }
                            .toString(Charsets.UTF_8.name()).trim()
                } finally { generated.dispose() }
                val authorize = exec(opened, authorizeKeyCommand(publicKey), stdin = null, canSend = allowed)
                if (authorize !is MacResult.Ok) return authorize
                val hostKey = hostKeys.seen ?: return MacResult.Failed("La Mac no presentó una clave de host.")
                // disconnect() completes before device deletion clears stored credentials. A login
                // cannot pass its guard and save after that clear, even while disk writes block.
                synchronized(stateLock) {
                    if (!allowed()) return MacResult.Failed("Conexión cancelada.")
                    store?.saveMacLogin(deviceId, MacLogin(user, privateKey, publicKey, hostKey))
                    session = opened
                    retained = true
                }
                MacResult.Ok("")
            } catch (e: JSchException) {
                describe(e, passwordLogin = true)
            } catch (_: Exception) {
                MacResult.Failed("No se pudo completar o guardar el inicio de sesión con la Mac. Volvé a intentar.")
            } finally {
                if (!retained) provisional?.disconnect()
            }
        }
    }

    fun run(script: MacScript, canSend: () -> Boolean = { true }): MacResult = synchronized(lock) {
        if (!canSend()) return MacResult.Failed("Orden cancelada: cambió el dispositivo.")
        val current = session?.takeIf { it.isConnected } ?: run {
            // Wi-Fi drops and Mac display sleep close idle sessions; reopen silently.
            val reopened = openWithSavedLogin()
            if (reopened !is MacResult.Ok) return reopened
            session ?: return MacResult.Failed("Sin sesión con la Mac.")
        }
        if (!canSend()) return MacResult.Failed("Orden cancelada: cambió el dispositivo.")
        exec(current, script.command, script.source, canSend)
    }

    private fun openWithSavedLogin(): MacResult {
        val (t, startedIn) = synchronized(stateLock) {
            (target ?: return MacResult.Failed("No hay ninguna Mac seleccionada.")) to generation
        }
        val login = store?.getMacLogin(t.deviceId)
            ?: return MacResult.LoginNeeded("Iniciá sesión con tu usuario de la Mac para autorizar OmniRemote.")
        var provisional: Session? = null
        var retained = false
        return try {
            val jsch = JSch().apply {
                hostKeyRepository = PinnedHostKey(expected = login.hostKey)
                addIdentity(KEY_COMMENT, login.privateKey.toByteArray(), login.publicKey.toByteArray(), null)
            }
            val opened = jsch.getSession(login.username, t.ip, t.port)
            provisional = opened
            opened.apply {
                setConfig("PreferredAuthentications", "publickey")
                configure()
                connect(CONNECT_TIMEOUT_MS)
            }
            synchronized(stateLock) {
                if (startedIn != generation) return MacResult.Failed("Conexión cancelada.")
                session = opened
                retained = true
            }
            MacResult.Ok("")
        } catch (e: JSchException) {
            describe(e, passwordLogin = false)
        } finally {
            if (!retained) provisional?.disconnect()
        }
    }

    private fun Session.configure() {
        // The pinned repository decides; "yes" makes a changed host key fail before auth.
        setConfig("StrictHostKeyChecking", "yes")
        serverAliveInterval = KEEPALIVE_MS
        serverAliveCountMax = 2
    }

    private fun closeSession() {
        val closing = session
        session = null
        closing?.disconnect()
    }

    private fun exec(session: Session, command: String, stdin: String?, canSend: () -> Boolean = { true }): MacResult {
        val channel = try {
            session.openChannel("exec") as ChannelExec
        } catch (e: JSchException) {
            return MacResult.Failed("La sesión con la Mac se cerró: ${e.message}")
        }
        val deadline = System.nanoTime() + commandTimeoutMs * 1_000_000L
        val expired = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        var watchdog: ScheduledFuture<*>? = null
        return try {
            // JSch channel writes can wait on the remote window and ignore interruption.
            // Closing this channel from outside that write also bounds stdin upload.
            watchdog = commandWatchdog.scheduleWithFixedDelay({
                if (System.nanoTime() >= deadline) expired.set(true)
                if (!runCatching(canSend).getOrDefault(false)) cancelled.set(true)
                if (expired.get() || cancelled.get()) channel.disconnect()
            }, 10, 10, TimeUnit.MILLISECONDS)
            channel.setCommand(command)
            val out = channel.inputStream
            val err = channel.errStream
            val input = channel.outputStream
            channel.connect(minOf(CONNECT_TIMEOUT_MS, commandTimeoutMs))
            if (!canSend() || cancelled.get()) return MacResult.Failed("Orden cancelada: cambió el destino.")
            if (System.nanoTime() >= deadline || expired.get()) return MacResult.Failed("La Mac tardó demasiado en responder. Volvé a intentar.")
            input.use { if (stdin != null) it.write(stdin.toByteArray()) }
            val stdoutBytes = ByteArrayOutputStream()
            val stderrBytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            fun drain(stream: java.io.InputStream, sink: ByteArrayOutputStream) {
                while (stream.available() > 0) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, stream.available()))
                    if (count <= 0) break
                    sink.write(buffer, 0, count)
                    check(stdoutBytes.size() + stderrBytes.size() <= 512 * 1024) { "Respuesta demasiado grande." }
                    check(System.nanoTime() < deadline) { "Se agotó el tiempo de respuesta de la Mac." }
                }
            }
            while (true) {
                if (!canSend() || Thread.currentThread().isInterrupted) return MacResult.Failed("Orden cancelada: cambió el destino.")
                if (System.nanoTime() >= deadline) return MacResult.Failed("La Mac tardó demasiado en responder. Volvé a intentar.")
                drain(out, stdoutBytes)
                drain(err, stderrBytes)
                if (channel.isClosed && out.available() == 0 && err.available() == 0) break
                Thread.sleep(10)
            }
            val stdout = stdoutBytes.toString(Charsets.UTF_8.name()).trim()
            val stderr = stderrBytes.toString(Charsets.UTF_8.name()).trim()
            if (channel.exitStatus == 0) MacResult.Ok(stdout)
            else MacResult.Failed(describeScriptError(stderr.ifBlank { stdout }))
        } catch (e: Exception) {
            MacResult.Failed(when {
                cancelled.get() -> "Orden cancelada: cambió el destino."
                expired.get() -> "La Mac tardó demasiado en responder. Volvé a intentar."
                else -> "La Mac no respondió: ${e.message}"
            })
        } finally {
            watchdog?.cancel(false)
            channel.disconnect()
        }
    }

    private fun describe(e: JSchException, passwordLogin: Boolean): MacResult {
        val message = e.message.orEmpty()
        return when {
            message.contains("Auth fail", ignoreCase = true) || message.contains("Auth cancel", ignoreCase = true) ->
                if (passwordLogin) MacResult.LoginNeeded("Usuario o contraseña incorrectos.")
                else MacResult.LoginNeeded("La Mac ya no acepta la clave de OmniRemote. Volvé a iniciar sesión.")
            message.contains("HostKey has been changed", ignoreCase = true) ->
                MacResult.Failed("La identidad de la Mac cambió. Si la reinstalaste, borrala y volvé a agregarla.")
            message.contains("Connection refused", ignoreCase = true) ->
                MacResult.Failed("La Mac rechazó la conexión. Activá Ajustes del Sistema > General > Compartir > Sesión remota.")
            message.contains("timeout", ignoreCase = true) || message.contains("timed out", ignoreCase = true) ||
                message.contains("No route", ignoreCase = true) || message.contains("unreachable", ignoreCase = true) ->
                MacResult.Failed("No se encontró la Mac. Verificá que esté despierta y en la misma red Wi-Fi.")
            else -> MacResult.Failed("No se pudo conectar con la Mac: $message")
        }
    }

    private class PinnedHostKey(private val expected: String?) : HostKeyRepository {
        @Volatile var seen: String? = null

        override fun check(host: String?, key: ByteArray?): Int {
            val presented = key?.let { HostKey(host ?: "", it).key } ?: return HostKeyRepository.NOT_INCLUDED
            seen = presented
            return if (expected == null || expected == presented) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "omniremote-pinned"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /** macOS answers password logins through PAM's keyboard-interactive prompt. */
    private class PasswordUserInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String?) = true
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) = Unit
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?
        ): Array<String> = Array(prompt?.size ?: 0) { password }
    }

    companion object {
        private val commandWatchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "mac-ssh-command-deadline").apply { isDaemon = true }
        }
        private const val CONNECT_TIMEOUT_MS = 6000
        private const val KEEPALIVE_MS = 15000
        private const val KEY_COMMENT = "omniremote@android"

        internal fun authorizeKeyCommand(publicKey: String): String {
            require(!publicKey.contains('\'') && !publicKey.contains('\n')) { "Unexpected public key format" }
            return "umask 077; mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && " +
                "(grep -qxF '$publicKey' ~/.ssh/authorized_keys || echo '$publicKey' >> ~/.ssh/authorized_keys)"
        }

        /** Turns osascript errors into the setting the user has to change on the Mac. */
        internal fun describeScriptError(error: String): String = when {
            error.contains("-1743") || error.contains("Not authorized to send Apple events", ignoreCase = true) ->
                "La Mac bloqueó el control del reproductor. En la Mac: Ajustes del Sistema > Privacidad y seguridad > Automatización > sshd-keygen-wrapper."
            error.contains("missing value") ->
                "La salida de audio actual de la Mac no permite cambiar el volumen."
            error.isBlank() -> "La Mac no pudo ejecutar el comando."
            else -> error.lineSequence().first().take(160)
        }
    }
}

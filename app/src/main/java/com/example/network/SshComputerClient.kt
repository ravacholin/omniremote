package com.example.network

import com.example.data.model.RemoteKey
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

/** Key-based login saved after the first password login, plus the computer's pinned host key. */
data class SshLogin(
    val username: String,
    val privateKey: String,
    val publicKey: String,
    val hostKey: String
)

interface SshLoginStore {
    fun getSshLogin(deviceId: Long): SshLogin?
    fun saveSshLogin(deviceId: Long, login: SshLogin)
    fun clearSshLogin(deviceId: Long)
}

sealed interface SshResult {
    data class Ok(val output: String) : SshResult
    data class LoginNeeded(val message: String) : SshResult
    data class Failed(val message: String) : SshResult
}

/** A command line for the computer's SSH server, with an optional script fed through stdin. */
interface SshCommand {
    val command: String
    val stdin: String?
}

/** Current output volume as both platforms report it: "level,muted". */
data class ComputerVolume(val level: Int, val muted: Boolean) {
    companion object {
        /** Parses "50,false". Outputs without volume control report something else ("missing value"). */
        fun parse(output: String): ComputerVolume? {
            val parts = output.trim().split(",")
            if (parts.size != 2) return null
            val level = parts[0].trim().toDoubleOrNull()?.toInt() ?: return null
            val muted = when (parts[1].trim()) {
                "true" -> true
                "false" -> false
                else -> return null
            }
            return ComputerVolume(level.coerceIn(0, 100), muted)
        }
    }
}

/** What differs between the computers controlled over SSH. */
interface SshPlatform {
    /** The computer as named in messages, e.g. "la Mac". */
    val displayName: String
    /** Where the user turns on the SSH server; shown when the connection is refused. */
    val enableSshHint: String
    val readVolume: SshCommand

    fun authorizeKey(publicKey: String): SshCommand
    fun setVolume(level: Int): SshCommand
    fun forKey(key: RemoteKey): SshCommand?

    /** True when [key] changes the volume, so its output carries the new [ComputerVolume]. */
    fun reportsVolume(key: RemoteKey): Boolean = when (key) {
        RemoteKey.VOL_UP, RemoteKey.VOL_DOWN, RemoteKey.MUTE -> true
        else -> false
    }

    /** Why a command that ran found nothing to control, or null when it did something. */
    fun idleMessage(output: String): String? = null

    /** Turns a failed command's output into the setting the user has to change. */
    fun describeScriptError(error: String): String
}

/**
 * Controls a computer through its built-in SSH server: nothing is installed on it.
 * The password is used once to authorize a key generated on the phone and is never stored.
 * Calls block, so run them off the main thread.
 */
class SshComputerClient(private val store: SshLoginStore?, val platform: SshPlatform) {
    private data class Target(val deviceId: Long, val ip: String, val port: Int)

    private val lock = Any()
    @Volatile private var session: Session? = null
    @Volatile private var target: Target? = null
    // Bumped by disconnect() so a login still in flight for the previous computer is dropped.
    @Volatile private var generation = 0

    private val name = platform.displayName
    private val capitalName = name.replaceFirstChar(Char::uppercaseChar)

    val isConnected: Boolean get() = session?.isConnected == true

    fun connect(deviceId: Long, ip: String, port: Int): SshResult = synchronized(lock) {
        closeSession()
        target = Target(deviceId, ip, port)
        openWithSavedLogin()
    }

    /** Does not wait for a command in flight; that command's session is closed under it. */
    fun disconnect() {
        generation++
        target = null
        closeSession()
    }

    /** First-time login: authorizes a new key on the computer, then keeps the session open. */
    fun login(deviceId: Long, ip: String, port: Int, username: String, password: String): SshResult = synchronized(lock) {
        closeSession()
        target = Target(deviceId, ip, port)
        val startedIn = generation
        val user = username.trim()
        val hostKeys = PinnedHostKey(expected = null)
        val newSession = try {
            JSch().apply { hostKeyRepository = hostKeys }.getSession(user, ip, port).apply {
                setPassword(password)
                userInfo = PasswordUserInfo(password)
                setConfig("PreferredAuthentications", "keyboard-interactive,password")
                configure()
                connect(CONNECT_TIMEOUT_MS)
            }
        } catch (e: JSchException) {
            return describe(e, passwordLogin = true)
        }

        val generated = try {
            KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        } catch (e: JSchException) {
            newSession.disconnect()
            return SshResult.Failed("No se pudo generar la clave: ${e.message}")
        }
        val privateKey = ByteArrayOutputStream().also { generated.writePrivateKey(it) }.toString(Charsets.UTF_8.name())
        val publicKey = ByteArrayOutputStream().also { generated.writePublicKey(it, KEY_COMMENT) }
            .toString(Charsets.UTF_8.name()).trim()
        generated.dispose()

        val authorize = exec(newSession, platform.authorizeKey(publicKey))
        if (authorize !is SshResult.Ok) {
            newSession.disconnect()
            return authorize
        }
        val hostKey = hostKeys.seen ?: run {
            newSession.disconnect()
            return SshResult.Failed("${capitalName} no presentó una clave de host.")
        }
        store?.saveSshLogin(deviceId, SshLogin(user, privateKey, publicKey, hostKey))
        if (startedIn != generation) {
            newSession.disconnect()
            return SshResult.Failed("Conexión cancelada.")
        }
        session = newSession
        SshResult.Ok("")
    }

    fun run(command: SshCommand): SshResult = synchronized(lock) {
        val current = session?.takeIf { it.isConnected } ?: run {
            // Wi-Fi drops and display sleep close idle sessions; reopen silently.
            val reopened = openWithSavedLogin()
            if (reopened !is SshResult.Ok) return reopened
            session ?: return SshResult.Failed("Sin sesión con ${name}.")
        }
        exec(current, command)
    }

    private fun openWithSavedLogin(): SshResult {
        val t = target ?: return SshResult.Failed("No hay ninguna computadora seleccionada.")
        val startedIn = generation
        val login = store?.getSshLogin(t.deviceId)
            ?: return SshResult.LoginNeeded("Iniciá sesión con tu usuario de ${name} para autorizar OmniRemote.")
        return try {
            val jsch = JSch().apply {
                hostKeyRepository = PinnedHostKey(expected = login.hostKey)
                addIdentity(KEY_COMMENT, login.privateKey.toByteArray(), login.publicKey.toByteArray(), null)
            }
            val opened = jsch.getSession(login.username, t.ip, t.port).apply {
                setConfig("PreferredAuthentications", "publickey")
                configure()
                connect(CONNECT_TIMEOUT_MS)
            }
            if (startedIn != generation) {
                opened.disconnect()
                return SshResult.Failed("Conexión cancelada.")
            }
            session = opened
            SshResult.Ok("")
        } catch (e: JSchException) {
            describe(e, passwordLogin = false)
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

    private fun exec(session: Session, command: SshCommand): SshResult {
        val channel = try {
            session.openChannel("exec") as ChannelExec
        } catch (e: JSchException) {
            return SshResult.Failed("La sesión con ${name} se cerró: ${e.message}")
        }
        return try {
            channel.setCommand(command.command)
            val out = channel.inputStream
            val err = channel.errStream
            val input = channel.outputStream
            channel.connect(CONNECT_TIMEOUT_MS)
            input.use { stdin -> command.stdin?.let { stdin.write(it.toByteArray()) } }
            val stdout = out.readBytes().toString(Charsets.UTF_8).trim()
            val stderr = err.readBytes().toString(Charsets.UTF_8).trim()
            val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (!channel.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(10)
            if (channel.exitStatus == 0) SshResult.Ok(stdout)
            else SshResult.Failed(platform.describeScriptError(stderr.ifBlank { stdout }))
        } catch (e: Exception) {
            SshResult.Failed("${capitalName} no respondió: ${e.message}")
        } finally {
            channel.disconnect()
        }
    }

    private fun describe(e: JSchException, passwordLogin: Boolean): SshResult {
        val message = e.message.orEmpty()
        return when {
            message.contains("Auth fail", ignoreCase = true) || message.contains("Auth cancel", ignoreCase = true) ->
                if (passwordLogin) SshResult.LoginNeeded("Usuario o contraseña incorrectos.")
                else SshResult.LoginNeeded("${capitalName} ya no acepta la clave de OmniRemote. Volvé a iniciar sesión.")
            message.contains("HostKey has been changed", ignoreCase = true) ->
                SshResult.Failed("La identidad de ${name} cambió. Si la reinstalaste, borrala y volvé a agregarla.")
            message.contains("Connection refused", ignoreCase = true) ->
                SshResult.Failed("${capitalName} rechazó la conexión. ${platform.enableSshHint}")
            message.contains("timeout", ignoreCase = true) || message.contains("timed out", ignoreCase = true) ||
                message.contains("No route", ignoreCase = true) || message.contains("unreachable", ignoreCase = true) ->
                SshResult.Failed("No se encontró ${name}. Verificá que esté despierta y en la misma red Wi-Fi.")
            else -> SshResult.Failed("No se pudo conectar con ${name}: $message")
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
        private const val CONNECT_TIMEOUT_MS = 6000
        private const val KEEPALIVE_MS = 15000
        private const val KEY_COMMENT = "omniremote@android"
    }
}

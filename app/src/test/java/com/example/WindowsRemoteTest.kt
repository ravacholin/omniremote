package com.example

import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.network.SshComputerClient
import com.example.network.SshLogin
import com.example.network.SshLoginStore
import com.example.network.SshResult
import com.example.network.WindowsPlatform
import com.example.network.WindowsScripts
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class WindowsRemoteTest {
    private val helper = WindowsScripts.HELPER_PATH

    @Test fun `volume and media keys map to the helper and other keys are unsupported`() {
        assertEquals("$helper volume-step 4", WindowsPlatform.forKey(RemoteKey.VOL_UP)!!.command)
        assertEquals("$helper volume-step -4", WindowsPlatform.forKey(RemoteKey.VOL_DOWN)!!.command)
        assertEquals("$helper mute", WindowsPlatform.forKey(RemoteKey.MUTE)!!.command)
        assertEquals("$helper key 179", WindowsPlatform.forKey(RemoteKey.PLAY_PAUSE)!!.command)
        assertEquals("$helper key 176", WindowsPlatform.forKey(RemoteKey.FAST_FORWARD)!!.command)
        assertEquals("$helper key 177", WindowsPlatform.forKey(RemoteKey.REWIND)!!.command)
        assertEquals("$helper key 178", WindowsPlatform.forKey(RemoteKey.STOP)!!.command)
        assertEquals("$helper display-off", WindowsPlatform.forKey(RemoteKey.POWER)!!.command)
        assertEquals("$helper volume-set 100", WindowsPlatform.setVolume(140).command)
        assertEquals("$helper volume-get", WindowsPlatform.readVolume.command)
        assertNull(WindowsPlatform.forKey(RemoteKey.HOME))
        assertNull(WindowsPlatform.forKey(RemoteKey.NUM_5))
        assertTrue(WindowsPlatform.reportsVolume(RemoteKey.VOL_UP))
        assertFalse(WindowsPlatform.reportsVolume(RemoteKey.PLAY_PAUSE))
        assertTrue(DeviceType.WINDOWS.isComputer)
        assertFalse(DeviceType.ANDROID_TV.isComputer)
    }

    @Test fun `powershell runs the stdin script through an encoded stub`() {
        val command = WindowsScripts.powershell("'hi'")
        assertEquals("'hi'", command.stdin)
        val encoded = command.command.substringAfter("-EncodedCommand ")
        val stub = String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE)
        assertTrue(stub, stub.contains("[ScriptBlock]::Create([Console]::In.ReadToEnd())"))
        assertTrue(stub, stub.contains("\$ErrorActionPreference = 'Stop'"))
        assertThrows(IllegalArgumentException::class.java) { WindowsScripts.powershell("'año'") }
    }

    @Test fun `authorize script adds the key for the user and for administrators`() {
        val script = WindowsScripts.authorizeKey("ecdsa-sha2-nistp256 AAAAE2 omniremote@android").stdin!!
        assertTrue(script.startsWith("\$key = 'ecdsa-sha2-nistp256 AAAAE2 omniremote@android'\n"))
        assertTrue(script.contains("'.ssh\\authorized_keys'"))
        assertTrue(script.contains("'ssh\\administrators_authorized_keys'"))
        assertTrue(script.contains("throw '${WindowsScripts.ADMIN_KEYS_DENIED}'"))
        assertFalse(script.contains("$$"))
        assertThrows(IllegalArgumentException::class.java) { WindowsScripts.authorizeKey("bad' key") }
    }

    @Test fun `installer embeds the helper source and registers the task`() {
        val script = WindowsScripts.install.stdin!!
        assertTrue(script.contains("namespace OmniRemote {"))
        assertTrue(script.contains("const string Version = \"${WindowsScripts.VERSION}\";"))
        assertTrue(script.contains("Register-ScheduledTask -TaskName 'OmniRemote'"))
        assertTrue(script.trimEnd().endsWith("'${WindowsScripts.VERSION}'"))
        assertFalse(script.contains("$$"))
        // The source sits in a single-quoted here-string, which a line starting with '@ would end.
        assertFalse(WindowsScripts.AGENT_SOURCE.lines().any { it.startsWith("'@") })
        assertEquals(
            WindowsScripts.AGENT_SOURCE.count { it == '{' },
            WindowsScripts.AGENT_SOURCE.count { it == '}' }
        )
    }

    @Test fun `helper errors point to what to fix on the PC`() {
        assertTrue(WindowsPlatform.describeScriptError("admin-keys-denied").contains("ProgramData"))
        val notFound = "'AppData\\Local\\OmniRemote\\omniremote.exe' is not recognized as an internal or external command,"
        assertTrue(WindowsPlatform.isSetupMissing(notFound))
        assertTrue(WindowsPlatform.isSetupMissing("'AppData' no se reconoce como un comando interno o externo"))
        assertTrue(WindowsPlatform.isSetupMissing("task-missing"))
        assertFalse(WindowsPlatform.isSetupMissing("Access is denied."))
        assertTrue(WindowsPlatform.describeScriptError("error: Element not found. (Exception from HRESULT: 0x80070490)")
            .contains("salida de audio"))
        assertEquals("Access is denied.", WindowsPlatform.describeScriptError("error: Access is denied.\nmore"))
        assertNotNull(WindowsPlatform.idleMessage(WindowsScripts.NO_SESSION))
        assertNull(WindowsPlatform.idleMessage("ok"))
    }

    @Test fun `login authorizes a key and installs the helper once, and a deleted helper is reinstalled`() {
        val dir = Files.createTempDirectory("windows-ssh")
        val server = FakeWindows(dir.resolve("host.ser"))
        val store = MemoryStore()
        try {
            val client = SshComputerClient(store, WindowsPlatform)
            assertTrue(client.connect(1, "127.0.0.1", server.port) is SshResult.LoginNeeded)
            assertEquals(SshResult.Ok(""), client.login(1, "127.0.0.1", server.port, "pablo", "secret"))
            assertEquals(1, server.authorizedKeys.size)
            assertEquals(1, server.installs)
            assertEquals(SshResult.Ok("48,false"), client.run(WindowsPlatform.forKey(RemoteKey.VOL_UP)!!))
            assertEquals("$helper volume-step 4", server.commands.last())
            client.disconnect()

            // An app restart logs in with the saved key and finds the helper already installed.
            val restarted = SshComputerClient(store, WindowsPlatform)
            assertEquals(SshResult.Ok(""), restarted.connect(1, "127.0.0.1", server.port))
            assertEquals(1, server.installs)

            server.installed = false
            assertEquals(SshResult.Ok("ok"), restarted.run(WindowsPlatform.forKey(RemoteKey.PLAY_PAUSE)!!))
            assertEquals(2, server.installs)

            server.signedIn = false
            assertEquals(SshResult.Ok(WindowsScripts.NO_SESSION), restarted.run(WindowsPlatform.forKey(RemoteKey.PLAY_PAUSE)!!))
            restarted.disconnect()
        } finally {
            server.stop()
        }
    }

    private class MemoryStore : SshLoginStore {
        val logins = HashMap<Long, SshLogin>()
        override fun getSshLogin(deviceId: Long) = logins[deviceId]
        override fun saveSshLogin(deviceId: Long, login: SshLogin) { logins[deviceId] = login }
        override fun clearSshLogin(deviceId: Long) { logins.remove(deviceId) }
    }

    /** SSH server standing in for Windows OpenSSH with cmd.exe as its shell. */
    private class FakeWindows(hostKey: java.nio.file.Path) {
        val authorizedKeys = CopyOnWriteArrayList<String>()
        val commands = CopyOnWriteArrayList<String>()
        @Volatile var installed = false
        @Volatile var installs = 0
        @Volatile var signedIn = true
        private val server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(hostKey)
            passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == "pablo" && password == "secret" }
            publickeyAuthenticator = PublickeyAuthenticator { user, key, _ ->
                user == "pablo" && authorizedKeys.any { PublicKeyEntry.parsePublicKeyEntry(it).resolvePublicKey(null, null, null) == key }
            }
            commandFactory = CommandFactory { _, command -> FakeCommand(command) }
            start()
        }
        val port: Int get() = server.port

        fun stop() = server.stop(true)

        private fun answer(command: String, stdin: String): Pair<Int, String> {
            if (command.startsWith("powershell.exe")) {
                Regex("^\\\$key = '([^']+)'").find(stdin)?.let {
                    authorizedKeys += it.groupValues[1]
                    return 0 to "ok"
                }
                if (stdin.contains("Register-ScheduledTask")) {
                    installed = true
                    installs++
                    return 0 to WindowsScripts.VERSION
                }
                return 1 to "unexpected script"
            }
            if (!command.startsWith(WindowsScripts.HELPER_PATH) || !installed) {
                return 1 to "'${command.substringBefore(' ')}' is not recognized as an internal or external command,"
            }
            return when (command.removePrefix(WindowsScripts.HELPER_PATH).trim()) {
                "version" -> 0 to WindowsScripts.VERSION
                "volume-step 4" -> 0 to "48,false"
                "key 179" -> 0 to if (signedIn) "ok" else WindowsScripts.NO_SESSION
                else -> 1 to "Unknown action"
            }
        }

        private inner class FakeCommand(private val command: String) : Command {
            private lateinit var input: InputStream
            private lateinit var output: OutputStream
            private lateinit var error: OutputStream
            private lateinit var exit: ExitCallback
            override fun setInputStream(stream: InputStream) { input = stream }
            override fun setOutputStream(stream: OutputStream) { output = stream }
            override fun setErrorStream(stream: OutputStream) { error = stream }
            override fun setExitCallback(callback: ExitCallback) { exit = callback }
            override fun destroy(channel: ChannelSession) = Unit
            override fun start(channel: ChannelSession, env: Environment) {
                thread {
                    val stdin = input.readBytes().toString(Charsets.UTF_8)
                    commands += command
                    val (status, text) = answer(command, stdin)
                    (if (status == 0) output else error).write("$text\r\n".toByteArray())
                    output.flush()
                    error.flush()
                    exit.onExit(status)
                }
            }
        }
    }
}

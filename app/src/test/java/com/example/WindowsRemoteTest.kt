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
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class WindowsRemoteTest {
    @Test fun `only volume keys map to Core Audio calls`() {
        assertTrue(WindowsPlatform.forKey(RemoteKey.VOL_UP)!!.stdin!!.endsWith("[OmniRemote.Audio]::Step(6)\n"))
        assertTrue(WindowsPlatform.forKey(RemoteKey.VOL_DOWN)!!.stdin!!.endsWith("[OmniRemote.Audio]::Step(-6)\n"))
        assertTrue(WindowsPlatform.forKey(RemoteKey.MUTE)!!.stdin!!.endsWith("[OmniRemote.Audio]::ToggleMute(0)\n"))
        assertTrue(WindowsPlatform.setVolume(140).stdin!!.endsWith("[OmniRemote.Audio]::Set(100)\n"))
        assertTrue(WindowsPlatform.readVolume.stdin!!.endsWith("[OmniRemote.Audio]::Get(0)\n"))
        assertNull(WindowsPlatform.forKey(RemoteKey.PLAY_PAUSE))
        assertNull(WindowsPlatform.forKey(RemoteKey.POWER))
        assertNull(WindowsPlatform.forKey(RemoteKey.HOME))
        assertTrue(WindowsPlatform.reportsVolume(RemoteKey.VOL_UP))
        assertTrue(DeviceType.WINDOWS.isComputer)
        assertFalse(DeviceType.ANDROID_TV.isComputer)
    }

    @Test fun `powershell runs the stdin script through an encoded stub`() {
        val command = WindowsScripts.powershell("'hi'")
        assertEquals("'hi'", command.stdin)
        val stub = String(Base64.getDecoder().decode(command.command.substringAfter("-EncodedCommand ")), Charsets.UTF_16LE)
        assertTrue(stub, stub.contains("[ScriptBlock]::Create([Console]::In.ReadToEnd())"))
        assertThrows(IllegalArgumentException::class.java) { WindowsScripts.powershell("'año'") }
        // The source sits in a single-quoted here-string, which a line starting with '@ would end.
        assertFalse(WindowsScripts.AUDIO_SOURCE.lines().any { it.startsWith("'@") })
        assertEquals(WindowsScripts.AUDIO_SOURCE.count { it == '{' }, WindowsScripts.AUDIO_SOURCE.count { it == '}' })
    }

    @Test fun `authorize script adds the key for the user and for administrators`() {
        val script = WindowsScripts.authorizeKey("ecdsa-sha2-nistp256 AAAAE2 omniremote@android").stdin!!
        assertTrue(script.startsWith("\$key = 'ecdsa-sha2-nistp256 AAAAE2 omniremote@android'\n"))
        assertTrue(script.contains("'.ssh\\authorized_keys'"))
        assertTrue(script.contains("'ssh\\administrators_authorized_keys'"))
        assertFalse(script.contains("$$"))
        assertThrows(IllegalArgumentException::class.java) { WindowsScripts.authorizeKey("bad' key") }
    }

    @Test fun `errors point to what to fix on the PC`() {
        assertTrue(WindowsPlatform.describeScriptError("admin-keys-denied").contains("ProgramData"))
        assertTrue(WindowsPlatform.describeScriptError("Element not found. (Exception from HRESULT: 0x80070490)")
            .contains("salida de audio"))
        assertEquals("Access is denied.", WindowsPlatform.describeScriptError("Access is denied.\nmore"))
    }

    @Test fun `password login authorizes a key that later logins use`() {
        val server = FakeWindows(Files.createTempDirectory("windows-ssh").resolve("host.ser"))
        val store = MemoryStore()
        try {
            val client = SshComputerClient(store, WindowsPlatform)
            assertTrue(client.connect(1, "127.0.0.1", server.port) is SshResult.LoginNeeded)
            assertEquals(SshResult.Ok(""), client.login(1, "127.0.0.1", server.port, "pablo", "secret"))
            assertEquals(1, server.authorizedKeys.size)
            assertEquals(SshResult.Ok("50,false"), client.run(WindowsPlatform.forKey(RemoteKey.VOL_UP)!!))
            client.disconnect()

            val restarted = SshComputerClient(store, WindowsPlatform)
            assertEquals(SshResult.Ok(""), restarted.connect(1, "127.0.0.1", server.port))
            assertEquals(SshResult.Ok("44,false"), restarted.run(WindowsPlatform.readVolume))
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

    /** SSH server standing in for Windows OpenSSH; PowerShell is faked from the script it receives. */
    private class FakeWindows(hostKey: Path) {
        val authorizedKeys = CopyOnWriteArrayList<String>()
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

        private fun answer(command: String, stdin: String): String? {
            if (!command.startsWith("powershell.exe ")) return null
            Regex("^\\\$key = '([^']+)'").find(stdin)?.let {
                authorizedKeys += it.groupValues[1]
                return "ok"
            }
            return when {
                stdin.endsWith("::Step(6)\n") -> "50,false"
                stdin.endsWith("::Get(0)\n") -> "44,false"
                else -> null
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
                    val text = answer(command, input.readBytes().toString(Charsets.UTF_8))
                    (if (text != null) output else error).write("${text ?: "unexpected command"}\r\n".toByteArray())
                    output.flush()
                    error.flush()
                    exit.onExit(if (text != null) 0 else 1)
                }
            }
        }
    }
}

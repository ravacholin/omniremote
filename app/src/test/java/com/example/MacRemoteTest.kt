package com.example

import com.example.data.model.RemoteKey
import com.example.network.MacLogin
import com.example.network.MacLoginStore
import com.example.network.MacResult
import com.example.network.MacScript
import com.example.network.MacScripts
import com.example.network.MacSshClient
import com.example.network.MacVolume
import com.example.network.NsdTvDiscoveryService
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class MacRemoteTest {
    @Test fun `volume and media keys map to osascript and other keys are unsupported`() {
        assertEquals(MacScript.Language.APPLESCRIPT, MacScripts.forKey(RemoteKey.VOL_UP)!!.language)
        assertTrue(MacScripts.forKey(RemoteKey.VOL_UP)!!.source.contains("(v + ${MacScripts.VOLUME_STEP})"))
        assertTrue(MacScripts.forKey(RemoteKey.VOL_DOWN)!!.source.contains("(v + -${MacScripts.VOLUME_STEP})"))
        val next = MacScripts.forKey(RemoteKey.FAST_FORWARD)!!
        assertEquals("/usr/bin/osascript -l JavaScript -", next.command)
        assertTrue(next.source.contains(".nextTrack()"))
        assertTrue(next.source.contains("mediaKey(17)"))
        assertTrue(MacScripts.forKey(RemoteKey.PLAY_PAUSE)!!.source.contains(".playpause()"))
        assertTrue(MacScripts.forKey(RemoteKey.REWIND)!!.source.contains(".previousTrack()"))
        assertTrue(MacScripts.setVolume(140).source.startsWith("set volume output volume 100\n"))
        assertNull(MacScripts.forKey(RemoteKey.HOME))
        assertNull(MacScripts.forKey(RemoteKey.NUM_5))
        assertTrue(MacScripts.reportsVolume(RemoteKey.MUTE))
        assertFalse(MacScripts.reportsVolume(RemoteKey.PLAY_PAUSE))
    }

    @Test fun `volume output parses and outputs without volume control are rejected`() {
        assertEquals(MacVolume(44, false), MacScripts.parseVolume("44,false\n"))
        assertEquals(MacVolume(100, true), MacScripts.parseVolume("100,true"))
        assertNull(MacScripts.parseVolume("missing value,false"))
        assertNull(MacScripts.parseVolume(""))
    }

    @Test fun `osascript errors point to the Mac setting to change`() {
        assertTrue(MacSshClient.describeScriptError("execution error: Not authorized to send Apple events to Spotify. (-1743)")
            .contains("Automatización"))
        assertTrue(MacSshClient.describeScriptError("Can’t make missing value into type number").contains("volumen"))
    }

    @Test fun `authorize command appends the key only once`() {
        val command = MacSshClient.authorizeKeyCommand("ecdsa-sha2-nistp256 AAAAE2 omniremote@android")
        assertTrue(command.contains("grep -qxF 'ecdsa-sha2-nistp256 AAAAE2 omniremote@android'"))
        assertThrows(IllegalArgumentException::class.java) { MacSshClient.authorizeKeyCommand("bad' key") }
    }

    @Test fun `macs are recognised from remote login or their airplay model`() {
        assertTrue(NsdTvDiscoveryService.isMacService("_ssh._tcp.", null))
        assertTrue(NsdTvDiscoveryService.isMacService("_airplay._tcp.", "MacBookAir10,1"))
        assertTrue(NsdTvDiscoveryService.isMacService("_airplay._tcp.", "Mac14,2"))
        assertFalse(NsdTvDiscoveryService.isMacService("_airplay._tcp.", "AppleTV11,1"))
        assertFalse(NsdTvDiscoveryService.isMacService("_googlecast._tcp.", null))
    }

    @Test fun `password login authorizes a key that later logins use, and a changed host key is refused`() {
        val dir = Files.createTempDirectory("mac-ssh")
        val server = FakeMac(dir.resolve("host.ser"))
        val store = MemoryStore()
        try {
            val client = MacSshClient(store)
            assertTrue(client.connect(1, "127.0.0.1", server.port) is MacResult.LoginNeeded)
            assertEquals(MacResult.LoginNeeded("Usuario o contraseña incorrectos."),
                client.login(1, "127.0.0.1", server.port, "pablo", "wrong"))
            assertEquals(MacResult.Ok(""), client.login(1, "127.0.0.1", server.port, "pablo", "secret"))
            assertEquals(1, server.authorizedKeys.size)
            assertEquals(MacResult.Ok("44,false"), client.run(MacScripts.forKey(RemoteKey.VOL_UP)!!))
            assertEquals("/usr/bin/osascript -", server.commands.last().first)
            assertTrue(server.commands.last().second.contains("set volume output volume (v + 6)"))
            client.disconnect()

            // A fresh client (app restart) logs in with the saved key alone.
            val restarted = MacSshClient(store)
            assertEquals(MacResult.Ok(""), restarted.connect(1, "127.0.0.1", server.port))
            assertEquals(MacResult.Ok("44,false"), restarted.run(MacScripts.readVolume))
            restarted.disconnect()
        } finally {
            server.stop()
        }

        val impostor = FakeMac(dir.resolve("other-host.ser"), acceptKeysFrom = store)
        try {
            val result = MacSshClient(store).connect(1, "127.0.0.1", impostor.port)
            assertTrue(result.toString(), result is MacResult.Failed && result.message.contains("identidad"))
        } finally {
            impostor.stop()
        }
    }

    private class MemoryStore : MacLoginStore {
        val logins = HashMap<Long, MacLogin>()
        override fun getMacLogin(deviceId: Long) = logins[deviceId]
        override fun saveMacLogin(deviceId: Long, login: MacLogin) { logins[deviceId] = login }
        override fun clearMacLogin(deviceId: Long) { logins.remove(deviceId) }
    }

    /** SSH server standing in for macOS Remote Login; osascript always reports "44,false". */
    private class FakeMac(hostKey: Path, acceptKeysFrom: MemoryStore? = null) {
        val authorizedKeys = CopyOnWriteArrayList<String>()
        val commands = CopyOnWriteArrayList<Pair<String, String>>()
        private val server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(hostKey)
            passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == "pablo" && password == "secret" }
            publickeyAuthenticator = PublickeyAuthenticator { user, key, _ ->
                val keys = authorizedKeys + acceptKeysFrom?.logins?.values?.map { it.publicKey }.orEmpty()
                user == "pablo" && keys.any { PublicKeyEntry.parsePublicKeyEntry(it).resolvePublicKey(null, null, null) == key }
            }
            commandFactory = CommandFactory { _, command -> FakeCommand(command) }
            start()
        }
        val port: Int get() = server.port

        fun stop() = server.stop(true)

        private inner class FakeCommand(private val command: String) : Command {
            private lateinit var input: InputStream
            private lateinit var output: OutputStream
            private lateinit var exit: ExitCallback
            override fun setInputStream(stream: InputStream) { input = stream }
            override fun setOutputStream(stream: OutputStream) { output = stream }
            override fun setErrorStream(stream: OutputStream) = Unit
            override fun setExitCallback(callback: ExitCallback) { exit = callback }
            override fun destroy(channel: ChannelSession) = Unit
            override fun start(channel: ChannelSession, env: Environment) {
                thread {
                    val isScript = command.startsWith("/usr/bin/osascript")
                    commands += command to (if (isScript) input.readBytes().toString(Charsets.UTF_8) else "")
                    Regex("echo '([^']+)' >>").find(command)?.let { authorizedKeys += it.groupValues[1] }
                    if (isScript) output.write("44,false\n".toByteArray())
                    output.flush()
                    exit.onExit(0)
                }
            }
        }
    }
}

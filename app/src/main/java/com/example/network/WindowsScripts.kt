package com.example.network

import com.example.data.model.RemoteKey
import okio.ByteString.Companion.toByteString

/** A command line for Windows OpenSSH; [stdin] carries a PowerShell script when there is one. */
data class WindowsCommand(override val command: String, override val stdin: String? = null) : SshCommand

/**
 * Windows 10/11 through the optional OpenSSH Server: volume and mute only.
 * Media keys sent over SSH never reach the signed-in user's desktop, so they are not offered.
 */
object WindowsPlatform : SshPlatform {
    override val displayName = "la PC"
    override val enableSshHint =
        "En la PC activá Configuración > Sistema > Características opcionales > Servidor OpenSSH y dejá en Automático el servicio sshd."
    override val readVolume: SshCommand = WindowsScripts.volume("Get", 0)

    override fun authorizeKey(publicKey: String): SshCommand = WindowsScripts.authorizeKey(publicKey)
    override fun setVolume(level: Int): SshCommand = WindowsScripts.volume("Set", level.coerceIn(0, 100))

    override fun forKey(key: RemoteKey): SshCommand? = when (key) {
        RemoteKey.VOL_UP -> WindowsScripts.volume("Step", WindowsScripts.VOLUME_STEP)
        RemoteKey.VOL_DOWN -> WindowsScripts.volume("Step", -WindowsScripts.VOLUME_STEP)
        RemoteKey.MUTE -> WindowsScripts.volume("ToggleMute", 0)
        else -> null
    }

    override fun describeScriptError(error: String): String = when {
        error.contains(WindowsScripts.ADMIN_KEYS_DENIED) ->
            "Windows no dejó guardar la clave para administradores en C:\\ProgramData\\ssh. Probá con un usuario de Windows que no sea administrador."
        error.contains("0x80070490") || error.contains("Element not found", ignoreCase = true) ->
            "La PC no tiene una salida de audio activa."
        error.isBlank() -> "La PC no pudo ejecutar el comando."
        else -> error.lineSequence().first().take(160)
    }
}

object WindowsScripts {
    const val VOLUME_STEP = 6 // like a Mac volume key; each press is a full PowerShell round trip
    const val ADMIN_KEYS_DENIED = "admin-keys-denied"

    /**
     * Runs the PowerShell script given on stdin. The stub is passed encoded so neither cmd.exe nor
     * PowerShell (whichever is the default shell) parses it; the script itself must be ASCII
     * because Windows reads stdin in the console code page.
     */
    fun powershell(script: String): WindowsCommand {
        require(script.all { it.code < 128 }) { "PowerShell scripts sent over stdin must be ASCII" }
        return WindowsCommand(
            "powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $ENCODED_STUB",
            script
        )
    }

    private const val STUB = "\$ErrorActionPreference = 'Stop'; \$ProgressPreference = 'SilentlyContinue'; " +
        "try { & ([ScriptBlock]::Create([Console]::In.ReadToEnd())) } " +
        "catch { [Console]::Error.WriteLine(\$_.Exception.Message); exit 1 }"

    private val ENCODED_STUB: String = STUB.toByteArray(Charsets.UTF_16LE).toByteString().base64()

    /**
     * Appends the key to the user's authorized_keys and, for administrators, to the file the
     * default sshd_config reads for them instead. Each file gets the key once.
     */
    fun authorizeKey(publicKey: String): WindowsCommand {
        require(!publicKey.contains('\'') && !publicKey.contains('\n') && publicKey.all { it.code < 128 }) {
            "Unexpected public key format"
        }
        return powershell(
            $$"""
$key = '$$publicKey'
function Add-Key($path) {
  $folder = Split-Path -Parent $path
  if (-not (Test-Path -LiteralPath $folder)) { New-Item -ItemType Directory -Force -Path $folder | Out-Null }
  $text = ''
  if (Test-Path -LiteralPath $path) { $text = [IO.File]::ReadAllText($path) }
  if (($text -split "`r?`n") -contains $key) { return }
  $separator = ''
  if ($text.Length -gt 0 -and -not $text.EndsWith("`n")) { $separator = "`r`n" }
  [IO.File]::AppendAllText($path, $separator + $key + "`r`n", [Text.Encoding]::ASCII)
}
Add-Key (Join-Path $env:USERPROFILE '.ssh\authorized_keys')
if ((whoami.exe /groups) -match 'S-1-5-32-544') {
  $admin = Join-Path $env:ProgramData 'ssh\administrators_authorized_keys'
  try { Add-Key $admin } catch { throw '$${ADMIN_KEYS_DENIED}' }
  icacls.exe $admin /inheritance:r /grant '*S-1-5-32-544:F' /grant '*S-1-5-18:F' | Out-Null
  if ($LASTEXITCODE -ne 0) { throw '$${ADMIN_KEYS_DENIED}' }
}
'ok'
""".trimStart()
        )
    }

    /** Calls `Audio.action(value)` from [AUDIO_SOURCE], which prints "level,muted". Nothing is left on disk. */
    fun volume(action: String, value: Int): WindowsCommand =
        powershell("Add-Type -TypeDefinition @'\n${AUDIO_SOURCE.trim()}\n'@\n[OmniRemote.Audio]::$action($value)\n")

    /** The default output's master volume through Core Audio, in C# 5 for Windows PowerShell's Add-Type. */
    internal val AUDIO_SOURCE = """
using System;
using System.Runtime.InteropServices;

namespace OmniRemote {
  [ComImport, Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IAudioEndpointVolume {
    int RegisterControlChangeNotify(IntPtr notify);
    int UnregisterControlChangeNotify(IntPtr notify);
    int GetChannelCount(out uint count);
    int SetMasterVolumeLevel(float level, ref Guid context);
    int SetMasterVolumeLevelScalar(float level, ref Guid context);
    int GetMasterVolumeLevel(out float level);
    int GetMasterVolumeLevelScalar(out float level);
    int SetChannelVolumeLevel(uint channel, float level, ref Guid context);
    int SetChannelVolumeLevelScalar(uint channel, float level, ref Guid context);
    int GetChannelVolumeLevel(uint channel, out float level);
    int GetChannelVolumeLevelScalar(uint channel, out float level);
    int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid context);
    int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
  }

  [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDevice {
    int Activate(ref Guid iid, int context, IntPtr parameters, [MarshalAs(UnmanagedType.IUnknown)] out object endpoint);
  }

  [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDeviceEnumerator {
    int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
    int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
  }

  [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
  class MMDeviceEnumerator { }

  public static class Audio {
    public static string Get(int unused) {
      IAudioEndpointVolume endpoint = Endpoint();
      float level;
      bool muted;
      Check(endpoint.GetMasterVolumeLevelScalar(out level));
      Check(endpoint.GetMute(out muted));
      return (int)Math.Round(level * 100) + "," + (muted ? "true" : "false");
    }

    public static string Set(int level) {
      return SetScalar(level / 100f);
    }

    public static string Step(int delta) {
      float level;
      Check(Endpoint().GetMasterVolumeLevelScalar(out level));
      return SetScalar(level + delta / 100f);
    }

    public static string ToggleMute(int unused) {
      IAudioEndpointVolume endpoint = Endpoint();
      bool muted;
      Check(endpoint.GetMute(out muted));
      Guid context = Guid.Empty;
      Check(endpoint.SetMute(!muted, ref context));
      return Get(0);
    }

    static string SetScalar(float level) {
      IAudioEndpointVolume endpoint = Endpoint();
      Guid context = Guid.Empty;
      Check(endpoint.SetMasterVolumeLevelScalar(Math.Max(0f, Math.Min(1f, level)), ref context));
      Check(endpoint.SetMute(false, ref context));
      return Get(0);
    }

    static IAudioEndpointVolume Endpoint() {
      IMMDeviceEnumerator devices = (IMMDeviceEnumerator)new MMDeviceEnumerator();
      IMMDevice device;
      Check(devices.GetDefaultAudioEndpoint(0, 1, out device));
      Guid iid = typeof(IAudioEndpointVolume).GUID;
      object endpoint;
      Check(device.Activate(ref iid, 23, IntPtr.Zero, out endpoint));
      return (IAudioEndpointVolume)endpoint;
    }

    static void Check(int result) {
      if (result < 0) Marshal.ThrowExceptionForHR(result);
    }
  }
}
"""
}

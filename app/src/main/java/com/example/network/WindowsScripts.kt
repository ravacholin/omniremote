package com.example.network

import com.example.data.model.RemoteKey
import okio.ByteString.Companion.toByteString

/** A command line for Windows OpenSSH; [stdin] carries a PowerShell script when there is one. */
data class WindowsCommand(override val command: String, override val stdin: String? = null) : SshCommand

/**
 * Windows 10/11 through the optional OpenSSH Server.
 *
 * Commands over SSH run outside the user's desktop, where simulated media keys go nowhere.
 * So the first login compiles a small helper with the C# compiler that ships with Windows and
 * registers a scheduled task that runs it inside the signed-in user's session. Volume is set
 * directly through Core Audio and only falls back to the task when that is not reachable.
 */
object WindowsPlatform : SshPlatform {
    override val displayName = "la PC"
    override val enableSshHint =
        "En la PC activá Configuración > Sistema > Características opcionales > Servidor OpenSSH y dejá en Automático el servicio sshd."
    override val readVolume: SshCommand = helper("volume-get")

    override fun authorizeKey(publicKey: String): SshCommand = WindowsScripts.authorizeKey(publicKey)
    override fun setVolume(level: Int): SshCommand = helper("volume-set ${level.coerceIn(0, 100)}")

    override fun forKey(key: RemoteKey): SshCommand? = when (key) {
        RemoteKey.VOL_UP -> helper("volume-step ${WindowsScripts.VOLUME_STEP}")
        RemoteKey.VOL_DOWN -> helper("volume-step -${WindowsScripts.VOLUME_STEP}")
        RemoteKey.MUTE -> helper("mute")
        RemoteKey.PLAY_PAUSE, RemoteKey.PLAY -> helper("key ${WindowsScripts.VK_MEDIA_PLAY_PAUSE}")
        RemoteKey.PAUSE, RemoteKey.STOP -> helper("key ${WindowsScripts.VK_MEDIA_STOP}")
        RemoteKey.FAST_FORWARD -> helper("key ${WindowsScripts.VK_MEDIA_NEXT_TRACK}")
        RemoteKey.REWIND -> helper("key ${WindowsScripts.VK_MEDIA_PREV_TRACK}")
        RemoteKey.POWER, RemoteKey.POWER_OFF -> helper("display-off")
        else -> null
    }

    /** Reinstalls only when the helper is missing, outdated or its scheduled task was deleted. */
    override fun prepare(run: (SshCommand) -> SshResult): SshResult {
        val installed = run(helper("version"))
        if (installed is SshResult.Ok && installed.output == WindowsScripts.VERSION) return installed
        return run(WindowsScripts.install)
    }

    override fun isSetupMissing(error: String): Boolean =
        isCommandNotFound(error) || error.contains(WindowsScripts.TASK_MISSING)

    override fun idleMessage(output: String): String? =
        if (output == WindowsScripts.NO_SESSION)
            "La PC no respondió desde el escritorio. Iniciá sesión en Windows y desbloqueala para controlar la reproducción."
        else null

    override fun describeScriptError(error: String): String = when {
        error.contains(WindowsScripts.ADMIN_KEYS_DENIED) ->
            "Windows no dejó guardar la clave para administradores en C:\\ProgramData\\ssh. Probá con un usuario de Windows que no sea administrador."
        isCommandNotFound(error) || error.contains(WindowsScripts.TASK_MISSING) ->
            "Falta el ayudante de OmniRemote en la PC. Volvé a conectarte para reinstalarlo."
        error.contains("0x80070490") || error.contains("Element not found", ignoreCase = true) ->
            "La PC no tiene una salida de audio activa."
        error.isBlank() -> "La PC no pudo ejecutar el comando."
        else -> error.lineSequence().first().removePrefix("error: ").take(160)
    }

    /** A missing helper as cmd.exe or PowerShell (whichever is the default shell) reports it, in English or Spanish. */
    private fun isCommandNotFound(error: String): Boolean =
        error.contains("is not recognized", ignoreCase = true) ||
            error.contains("no se reconoce", ignoreCase = true) ||
            error.contains("cannot find the path", ignoreCase = true) ||
            error.contains("no puede encontrar la ruta", ignoreCase = true)

    // sshd starts sessions in the user's profile folder, so this works under cmd and PowerShell alike.
    private fun helper(args: String) = WindowsCommand("${WindowsScripts.HELPER_PATH} $args")
}

object WindowsScripts {
    /** Bumped whenever [AGENT_SOURCE] changes so connected PCs recompile the helper. */
    const val VERSION = "omniremote-1"
    const val VOLUME_STEP = 4 // twice a Windows volume key step, so held keys keep up with SSH round trips
    const val VK_MEDIA_NEXT_TRACK = 0xB0
    const val VK_MEDIA_PREV_TRACK = 0xB1
    const val VK_MEDIA_STOP = 0xB2
    const val VK_MEDIA_PLAY_PAUSE = 0xB3
    const val NO_SESSION = "no-session"
    const val TASK_MISSING = "task-missing"
    const val ADMIN_KEYS_DENIED = "admin-keys-denied"
    const val TASK_NAME = "OmniRemote"
    const val HELPER_PATH = "AppData\\Local\\OmniRemote\\omniremote.exe"

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

    /**
     * Compiles the helper twice with the .NET Framework compiler that ships with Windows: a
     * console build for SSH and a windowless build for the scheduled task, which runs in the
     * signed-in user's desktop without flashing a window. The task only runs while that user
     * is signed in.
     */
    val install: WindowsCommand by lazy {
        powershell(
            $$"""
$dir = Join-Path $env:USERPROFILE 'AppData\Local\OmniRemote'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$source = Join-Path $dir 'omniremote.cs'
[IO.File]::WriteAllText($source, @'
$${AGENT_SOURCE.trim()}
'@)
$csc = Join-Path ([Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()) 'csc.exe'
foreach ($build in @(@('omniremote.exe', 'exe'), @('omniremote-agent.exe', 'winexe'))) {
  $log = & $csc /nologo /optimize "/target:$($build[1])" "/out:$(Join-Path $dir $build[0])" $source
  if ($LASTEXITCODE -ne 0) { throw "csc: $log" }
}
$user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$action = New-ScheduledTaskAction -Execute (Join-Path $dir 'omniremote-agent.exe') -Argument '--task'
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances Parallel -ExecutionTimeLimit (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName '$$TASK_NAME' -Action $action -Principal $principal -Settings $settings -Force | Out-Null
'$$VERSION'
""".trimStart()
        )
    }

    /**
     * The helper, in C# 5 for the .NET Framework compiler every Windows 10/11 has.
     * `omniremote.exe <action> [value]` prints "level,muted" for volume actions and "ok" for keys.
     * Keys always go through the scheduled task; volume does too if Core Audio is not reachable
     * from the SSH session. Requests and results are files next to the helper.
     */
    internal val AGENT_SOURCE = """
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

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

  public static class Program {
    const string Version = "$VERSION";
    const string TaskName = "$TASK_NAME";

    [DllImport("user32.dll")]
    static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);

    [DllImport("user32.dll")]
    static extern bool PostMessage(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);

    static string Dir { get { return AppDomain.CurrentDomain.BaseDirectory; } }

    [STAThread]
    public static int Main(string[] args) {
      try {
        if (args.Length > 0 && args[0] == "--task") { ServeRequests(); return 0; }
        if (args.Length > 0 && args[0] == "version") {
          Console.WriteLine(Schtasks("/query /tn " + TaskName) == 0 ? Version : "$TASK_MISSING");
          return 0;
        }
        string action = args.Length > 0 ? args[0] : "";
        int value = args.Length > 1 ? int.Parse(args[1]) : 0;
        string output;
        if (action == "key" || action == "display-off") {
          output = ViaTask(action, value);
        } else {
          try { output = Run(action, value); }
          catch (Exception) { output = ViaTask(action, value); }
        }
        if (output.StartsWith("error: ")) throw new Exception(output);
        Console.WriteLine(output);
        return 0;
      } catch (Exception e) {
        Console.Error.WriteLine(e.Message);
        return 1;
      }
    }

    static string Run(string action, int value) {
      switch (action) {
        case "volume-get": return Report();
        case "volume-set": return SetVolume(value / 100f);
        case "volume-step": return SetVolume(Level() + value / 100f);
        case "mute": {
          IAudioEndpointVolume endpoint = Endpoint();
          bool muted;
          Check(endpoint.GetMute(out muted));
          Guid context = Guid.Empty;
          Check(endpoint.SetMute(!muted, ref context));
          return Report();
        }
        case "key":
          keybd_event((byte)value, 0, 1, UIntPtr.Zero);
          keybd_event((byte)value, 0, 3, UIntPtr.Zero);
          return "ok";
        case "display-off":
          PostMessage(new IntPtr(0xFFFF), 0x0112, new IntPtr(0xF170), new IntPtr(2));
          return "ok";
        default: throw new ArgumentException("Unknown action: " + action);
      }
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

    static float Level() {
      float level;
      Check(Endpoint().GetMasterVolumeLevelScalar(out level));
      return level;
    }

    static string SetVolume(float level) {
      IAudioEndpointVolume endpoint = Endpoint();
      Guid context = Guid.Empty;
      Check(endpoint.SetMasterVolumeLevelScalar(Math.Max(0f, Math.Min(1f, level)), ref context));
      Check(endpoint.SetMute(false, ref context));
      return Report();
    }

    static string Report() {
      IAudioEndpointVolume endpoint = Endpoint();
      float level;
      bool muted;
      Check(endpoint.GetMasterVolumeLevelScalar(out level));
      Check(endpoint.GetMute(out muted));
      return (int)Math.Round(level * 100) + "," + (muted ? "true" : "false");
    }

    static void Check(int result) {
      if (result < 0) Marshal.ThrowExceptionForHR(result);
    }

    static int Schtasks(string arguments) {
      ProcessStartInfo info = new ProcessStartInfo("schtasks.exe", arguments);
      info.UseShellExecute = false;
      info.CreateNoWindow = true;
      info.RedirectStandardOutput = true;
      info.RedirectStandardError = true;
      using (Process process = Process.Start(info)) {
        process.StandardOutput.ReadToEnd();
        process.StandardError.ReadToEnd();
        process.WaitForExit();
        return process.ExitCode;
      }
    }

    // Hands the action to the task running in the user's desktop and waits for its answer.
    static string ViaTask(string action, int value) {
      string id = Guid.NewGuid().ToString("N");
      string request = Path.Combine(Dir, id + ".request");
      string result = Path.Combine(Dir, id + ".result");
      File.WriteAllText(request, action + " " + value);
      if (Schtasks("/run /tn " + TaskName) != 0) {
        File.Delete(request);
        throw new Exception("$TASK_MISSING");
      }
      for (int i = 0; i < 40; i++) {
        if (File.Exists(result)) {
          string output = File.ReadAllText(result).Trim();
          File.Delete(result);
          return output;
        }
        Thread.Sleep(100);
      }
      // Nobody is signed in, so the task did not start; drop the request unless it was taken.
      File.Delete(request);
      return "$NO_SESSION";
    }

    // Runs as the scheduled task: answers every pending request, claiming each by renaming it.
    static void ServeRequests() {
      foreach (string request in Directory.GetFiles(Dir, "*.request")) {
        string claimed = request + ".working";
        try { File.Move(request, claimed); } catch (IOException) { continue; }
        string output;
        try {
          string[] parts = File.ReadAllText(claimed).Trim().Split(' ');
          output = Run(parts[0], parts.Length > 1 ? int.Parse(parts[1]) : 0);
        } catch (Exception e) {
          output = "error: " + e.Message;
        }
        string id = Path.GetFileNameWithoutExtension(request);
        string temp = Path.Combine(Dir, id + ".tmp");
        File.WriteAllText(temp, output);
        File.Move(temp, Path.Combine(Dir, id + ".result"));
        File.Delete(claimed);
      }
      // Answers that arrived after their request timed out.
      foreach (string stale in Directory.GetFiles(Dir, "*.result")) {
        if (File.GetLastWriteTimeUtc(stale) < DateTime.UtcNow.AddMinutes(-1)) File.Delete(stale);
      }
    }
  }
}
"""
}

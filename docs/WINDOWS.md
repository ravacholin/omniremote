# Windows volume and media

OmniRemote controls a Windows 10 (1809 or later) or Windows 11 PC's volume and media over
Wi-Fi through Windows' optional OpenSSH Server. Nothing has to be downloaded on the PC: the
app compiles a small helper with the C# compiler that ships with Windows.

## One-time setup on the PC

1. **Settings > System > Optional features > Add a feature > OpenSSH Server**.
2. In an administrator PowerShell, start it now and on every boot:
   `Start-Service sshd; Set-Service sshd -StartupType Automatic`.
   The installer opens port 22 in Windows Firewall; check that the Wi-Fi network is
   marked **Private**.
3. In OmniRemote, add the PC by IP with the **PC con Windows** platform (port 22; Windows
   does not advertise SSH, so it does not show up in scans). Sign in with the Windows user
   name (the folder name under `C:\Users`) and the account password, not the PIN. For a
   Microsoft account, use its password.

The password is used once: the phone generates an ECDSA key and adds it to
`%USERPROFILE%\.ssh\authorized_keys`. Administrators also get it in
`C:\ProgramData\ssh\administrators_authorized_keys`, which the default `sshd_config`
reads for them instead, restricted to Administrators and SYSTEM. The PC's host key is
pinned on first login; a changed host key is refused.

## What the first login leaves on the PC

Commands over SSH run outside the signed-in user's desktop, where simulated media keys go
nowhere. So on login OmniRemote:

- writes `%USERPROFILE%\AppData\Local\OmniRemote\omniremote.cs` and compiles it with the
  .NET Framework `csc.exe` into `omniremote.exe` (run over SSH) and `omniremote-agent.exe`
  (no window);
- registers the scheduled task **OmniRemote**, which runs `omniremote-agent.exe` in the
  signed-in user's desktop when asked (it has no trigger of its own).

Each later connection runs `omniremote.exe version` and recompiles only when the helper is
missing, outdated or the task was deleted.

To remove everything: `Unregister-ScheduledTask OmniRemote`, delete
`%USERPROFILE%\AppData\Local\OmniRemote`, and remove the `omniremote@android` line from the
authorized keys files above.

## What each control does

| Control | PC command |
| --- | --- |
| Volume +/- | Core Audio master volume ±4, unmutes |
| Slider | Core Audio master volume N (only the latest drag position is sent) |
| Mute | toggles the master mute |
| Play/pause, previous, next, stop | the media key, sent from the user's desktop by the scheduled task, so it reaches Spotify, browsers and any app that shows in Windows' media flyout |
| Power (connected) | turns the display off |

Volume is set directly from the SSH session; if Core Audio cannot be reached from there,
it goes through the scheduled task as well.

## Limits

- Media keys and display off need a signed-in, unlocked Windows session. Without one the
  app says so instead of failing silently.
- Media keys answer more slowly than volume: each one goes through SSH and then the
  scheduled task.
- `omniremote.exe` is found relative to the profile folder, where Windows OpenSSH starts
  every session.
- Sleep stops the PC from answering. Wake-on-LAN only works when the network adapter allows
  it (Device Manager > adapter > Power Management > "Allow this device to wake the computer").

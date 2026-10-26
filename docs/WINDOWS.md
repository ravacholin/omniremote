# Windows volume

OmniRemote controls a Windows 10 (1809 or later) or Windows 11 PC's volume over Wi-Fi
through Windows' optional OpenSSH Server. Nothing is installed or left on the PC.

## One-time setup on the PC

1. **Settings > System > Optional features > Add a feature > OpenSSH Server**.
2. In an administrator PowerShell: `Start-Service sshd; Set-Service sshd -StartupType Automatic`.
   Check that the Wi-Fi network is marked **Private** so the firewall lets port 22 in.
3. In OmniRemote, add the PC by IP with the **PC con Windows** platform (port 22; Windows
   does not advertise SSH, so it does not show up in scans). Sign in with the Windows user
   name (the folder name under `C:\Users`) and the account password, not the PIN.

The password is used once: the phone generates an ECDSA key and adds it to
`%USERPROFILE%\.ssh\authorized_keys`. Administrators also get it in
`C:\ProgramData\ssh\administrators_authorized_keys`, which the default `sshd_config` reads
for them instead. The PC's host key is pinned on first login; a changed host key is refused.

## What each control does

| Control | PC command |
| --- | --- |
| Volume +/- | default output's master volume ±6 through Core Audio, unmutes |
| Slider | master volume N (only the latest drag position is sent) |
| Mute | toggles the master mute |

Each press starts PowerShell and compiles the Core Audio call, so it answers more slowly
than on a Mac. Play/pause and other media keys are not offered: keys sent over
SSH never reach the signed-in user's desktop.

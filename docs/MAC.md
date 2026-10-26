# Mac volume and media

OmniRemote controls a Mac's volume and media over Wi-Fi without installing anything
on the Mac. It uses macOS's built-in Remote Login (SSH) server and `osascript`.

## One-time setup on the Mac

1. **System Settings > General > Sharing > Remote Login**: on.
2. In OmniRemote, scan (Macs advertise `_ssh._tcp`) or add the Mac by IP with the
   **Mac** platform (port 22), then sign in with the Mac's short user name and password.
   The password is used once: the phone generates an ECDSA key, appends it to
   `~/.ssh/authorized_keys` (once) and logs in with that key from then on. The Mac's
   host key is pinned on first login; a changed host key is refused.
3. The first time a player is controlled, macOS asks whether `sshd-keygen-wrapper`
   may control Music or Spotify. Allow it (Privacy & Security > Automation).
4. Optional: to pause or skip in any app (YouTube in a browser, etc.), add
   `sshd-keygen-wrapper` under Privacy & Security > **Accessibility**. Without it the
   media keys still work for Music and Spotify.

## What each control does

| Control | Mac command |
| --- | --- |
| Volume +/- | `set volume output volume` ±6 (one macOS volume step), unmutes |
| Slider | `set volume output volume N` (only the latest drag position is sent) |
| Mute | toggles `output muted` |
| Play/pause, previous, next | the Spotify or Music player that is playing; otherwise the system media key (needs Accessibility); otherwise the first open player |
| Power (connected) | `pmset displaysleepnow` (display off; the Mac stays awake) |

## Limits

- The Mac must be awake and logged in as the same user. A closed lid means sleep;
  Wake-on-LAN only works with "Wake for network access" turned on.
- Digital outputs without volume control (some HDMI/USB devices) report no volume.

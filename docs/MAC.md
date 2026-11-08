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

## Choosing a player

Open **Controlar** in the Mac panel. **Automático** keeps the existing routing
heuristic. A manual selection directs media commands to that app or specific
Chrome tab and is shared with the notification mini remote. Selection alone
does not play/pause anything or bring windows forward.

The initial catalog includes Spotify and Music apps, plus YouTube, YouTube Music
and Spotify web in regular Chrome windows. Paused players are included. Unrelated
tabs, incognito windows and pages without a loaded player are excluded. Equal
titles are distinguished by window/tab IDs. These IDs are session-only.

Chrome additionally needs macOS **Automation** permission for the SSH automation
process to control Google Chrome, and Chrome's **View > Developer > Allow
JavaScript from Apple Events** option enabled (wording may vary by version).
OmniRemote reports missing configuration without changing the settings.
Accessibility is only needed for the existing Automatic system-media-key path.

Manual selection stays pinned when another app starts playing. Closing it or
changing its service blocks its controls; OmniRemote never falls back to another
player. Next/autoplay within the same service keeps the selected tab. An old tap
is rejected if its content changed before execution; press again after refresh.
Moving/reordering a tab keeps its identity. Reopening the app validates the source;
changing Mac, restarting the phone process or restarting Chrome does not restore
a tab by title/URL.

Only supported controls are enabled. Previous/next refer to tracks or videos,
not browser history or seeking seconds; ads can disable skipping. Volume and mute
always control **the Mac's output**. Spotify can follow a Spotify Connect session
on another output device; the selector does not switch that device.

Discovery runs on opening the selector, manual refresh or reconnect while it is
open. There is no background polling. The notification uses the same source and
shows only the service name, not private song/video titles. Open the app to change
the source. Old notification media buttons are rejected after a source change.

The web adapters operate on DOM/media elements in Chrome's isolated AppleScript
world. They do not use private site JavaScript APIs. Site changes, autoplay,
cross-origin frames, unavailable sessions, suspended tabs and Chrome policy may
limit functionality. Command acknowledgement is distinct from observed playback.
**Web compatibility still requires validation on an actual Mac**; Linux tests
exercise controlled players and cannot certify the user's Chrome/service version.

## What each control does

| Control | Mac command |
| --- | --- |
| Volume +/- | `set volume output volume` ±6 (one macOS volume step), unmutes |
| Slider | `set volume output volume N` (only the latest drag position is sent) |
| Mute | toggles `output muted` |
| Play/pause, previous, next | selected app/tab; in Automatic: playing Spotify/Music, then system media key (Accessibility), then an open player |
| Power (connected) | `pmset displaysleepnow` (display off; the Mac stays awake) |

## Limits

- The Mac must be awake and logged in as the same user. A closed lid means sleep;
  Wake-on-LAN only works with "Wake for network access" turned on.
- Digital outputs without volume control (some HDMI/USB devices) report no volume.

## Validation checklist

Automated: `source /workspace/.tooling/env.sh`, then
`gradle --console=plain --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
The standalone browser/JXA harness uses `node --test scripts/mac-media.test.cjs`.
No Node dependency is included in the Android app.

On an awake, logged-in Mac, record macOS/Chrome versions and run:

- [ ] Spotify app: play/pause and previous/next while a YouTube tab is also playing.
- [ ] Music app: play/pause and previous/next without affecting Spotify/Chrome.
- [ ] YouTube: two equal-titled tabs in different windows, paused media, moving a tab,
      next with/without a playlist, and Chrome behind another application.
- [ ] YouTube Music: play/pause and queue controls, song changes and live playback.
- [ ] Spotify web: play/pause and track controls, including a localized interface;
      document whether playback is local or follows Spotify Connect.
- [ ] Deny Automation or disable JavaScript from Apple Events: explain the setting,
      keep other players and system volume usable.
- [ ] Close the player, navigate to another service, suspend a tab, restart Chrome
      and reconnect SSH: never redirect an old command to another player.
- [ ] Change source and then tap an older notification: reject its media command;
      new buttons share the selected source, and volume remains general.
- [ ] Start playback once visibly if autoplay blocks it; distinguish sent orders
      from observed state. Check advertising does not enable generic ad skipping.

Physical validation status: pending; no Mac is attached to the development environment.

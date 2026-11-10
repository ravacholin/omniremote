# Brightness shortcut

The D-pad's former Source button is now Brightness (sun icon).

On Android TV, **TvDimmer - NightCompanion** is included under **Tools** with its
verified package name `com.tribalapps.tvdimmer`. Database version 3 adds this entry
once to saved Android TVs, preserving existing shortcuts and custom dimmer entries.
The brightness button uses the saved TVDimmer entry regardless of the current Apps
search or favorites filter. TVDimmer must still be installed on the TV.

Brightness and the TVDimmer entry in Apps now use **ADB over TCP** to launch the
installed app directly. They never open Google Play. Normal remote pairing and
navigation still use Android TV Remote Service v2.

## One-time ADB setup

1. Install TVDimmer on the TV/FlowBox first.
2. Enable Developer options on that device (normally Settings > About > press
   Build seven times), then enable **Network debugging / ADB over TCP**.
3. In OmniRemote press Brightness, enter the ADB TCP port (normally **5555**) and
   choose **Conectar y abrir**. This port is separate from Remote v2's port.
4. Accept the authorization prompt **on the TV** and select Always allow for
   OmniRemote. The authorization wait is bounded to 25 seconds; retry if needed.
5. Subsequent Brightness presses open TVDimmer directly. The saved Android TV card
   also has an ADB button for retrying, changing the port or forgetting settings.

**Wireless debugging with a pairing code/TLS is not this TCP mode.** Its pairing
and connection ports cannot be substituted here. If the box only exposes that
mode, enable classic TCP from a computer over an authorized USB connection with
`adb tcpip 5555`, if the device permits it. Some firmwares offer no usable TCP
mode. TCP debugging may need reenabling after a FlowBox reboot. OmniRemote does
not enable debugging remotely or modify the box's developer settings.

The app stores the port per saved TV and a separate RSA key in private no-backup
storage; ordinary app restarts preserve it. Reinstallation or revoking debugging
authorizations on the TV requires authorizing again. Forgetting ADB in OmniRemote
removes that TV's port; revoke the authorization on the TV to revoke access.

`am start -W` targets the saved dimmer package's MAIN/LEANBACK_LAUNCHER activity.
Success requires the shell exit marker and `Status: ok`, not just a written frame.
Errors show setup/retry guidance without disconnecting the regular remote or
falling back to Play Store. Concurrent taps are ignored; device changes,
background disconnect, deletion and dismissal cancel pending sockets and results.

On Samsung Tizen the same button is labeled **Bluetooth** and sends a normal-menu
navigation sequence targeting the user's **UN50RU7100GCFV (2019)**:
Settings > Sound > Sound Output > Bluetooth Speaker List. It leaves selection and
pairing to the user. The sequence takes several seconds, ignores repeated taps
while running, and cancels on manual keys, text entry, app launch, or device switch.
The remote protocol cannot inspect focus. This sequence has not been validated on
physical hardware and may need adjustment for menu layout or timing differences.

## Validation on hardware

1. Connect to an Android TV with TVDimmer installed and its entry saved in Apps.
2. Tap Brightness, configure TCP, authorize OmniRemote on the TV and check that TVDimmer opens without Google Play. Repeat after reconnecting and restarting OmniRemote.
3. Check the sun icon and Brightness accessibility label on small and large screens.
4. Remove the app entry and tap Brightness; check the setup guidance.
5. Select the Samsung RU7100, tap Bluetooth, and check that the speaker list opens.
6. Verify that Back or switching TVs cancels an in-progress Samsung sequence.

Automated local-daemon tests validate ADB authorization frames, signatures, stream
acknowledgments, output/status parsing, malformed frames, input validation and
cancellation. Coordinator and Compose tests validate setup/persistence/routing.
Existing TLS tests cover other apps and normal Remote v2 operation. No physical
FlowBox or Android device is attached; actual TCP availability, authorization,
and foreground launch still require hardware verification.

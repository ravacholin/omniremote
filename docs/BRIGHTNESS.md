# Brightness shortcut

The D-pad's former Source button is now Brightness (sun icon).

On Android TV, **TvDimmer - NightCompanion** is included under **Tools** with its
verified package name `com.tribalapps.tvdimmer`. Database version 3 adds this entry
once to saved Android TVs, preserving existing shortcuts and custom dimmer entries.
The brightness button uses the saved TVDimmer entry regardless of the current Apps
search or favorites filter. TVDimmer must still be installed on the TV.

## FlowBox opening without ADB (1.9)

The user verified on their FlowBox-Z3 that entering TVDimmer's Play Store page
focuses **Open**, and one OK from OmniRemote launches the installed app. The
Brightness button and TVDimmer Apps entry now automate that verified sequence
using the existing Remote v2 connection. After the store request's rejection
window, wait two seconds and send exactly one ENTER. No developer options or ADB
setup is required for this path. Play Store appears briefly during navigation.

Repeated taps are ignored while the sequence runs. Manual remote keys, text,
other app launches, device changes, disconnects and RemoteError cancel the pending
confirmation. The final key write checks cancellation and the current connection.
The log reports that Open was selected, not that foreground launch was observed.

This depends on the installed app's page continuing to focus Open. A store layout
change or a different initially focused action can invalidate the sequence.
The user verified the manual steps; automatic loading time remains a physical
check. If loading takes longer, the delay must be adjusted based on that result.

## Optional ADB mode (devices that support it)

ADB is available only through the saved-device ADB configuration entry. The
user's FlowBox does not expose developer options, so it is not the default path.

## One-time ADB setup

1. Install TVDimmer on the TV/FlowBox first.
2. Enable Developer options on that device (normally Settings > About > press
   Build seven times), then enable **Network debugging / ADB over TCP**.
3. In OmniRemote use the saved Android TV card's ADB button, enter the ADB TCP port (normally **5555**) and
   choose **Conectar y abrir**. This port is separate from Remote v2's port.
4. Accept the authorization prompt **on the TV** and select Always allow for
   OmniRemote. The authorization wait is bounded to 25 seconds; retry if needed.
5. Use the saved Android TV card's ADB button to retry, change the port or forget settings.
   The Brightness button uses the Remote v2 store/Open sequence described above.

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
Errors in the optional ADB mode show setup/retry guidance without disconnecting the regular remote. Concurrent taps are ignored; device changes,
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
2. Tap Brightness and verify the store page appears briefly, followed by TVDimmer opening automatically. Repeat after reconnecting and restarting OmniRemote. Verify Back cancels during the wait.
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

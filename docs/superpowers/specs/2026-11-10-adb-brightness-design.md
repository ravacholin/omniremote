# Direct Android TV brightness launch

The user approved adding ADB to OmniRemote so Brightness opens the installed
TVDimmer app instead of its Google Play listing. Keep normal remote pairing and
Samsung Bluetooth behavior intact.

## Approach

Use authenticated ADB over TCP, with an optional port saved per Android TV.
Default to 5555 and connect only when explicitly configured or Brightness is
pressed. Generate a separate persistent RSA identity in no-backup app storage.
The TV must allow network debugging and authorize OmniRemote on screen once.
Wireless debugging TLS/code pairing is a different protocol and is not included;
explain this in setup, including the USB `adb tcpip 5555` alternative and possible
need to reenable TCP after a TV restart. Do not alter the regular remote port.

Alternatives considered: app links cannot reliably open TVDimmer; blind presses
on Play Store cannot determine whether the current action is Open or Install.
A companion TV app would require another installation. ADB is the approach the
user selected; the TCP implementation details are implementation choices.

## Behavior

The first Brightness press opens setup. Connect and open attempts authorization
and launches the saved TVDimmer package using MAIN/LEANBACK_LAUNCHER with
`am start -W`. Save the port only after confirmed command success. Subsequent
presses use ADB directly. Errors return to setup with useful guidance, without
opening Play Store or invalidating the normal remote connection. Allow reopening
setup from the saved Android TV card, and forgetting its ADB configuration.

Bound connect/auth/command time and output, validate packet headers/IDs and
package/port input, and close sockets on cancellation. Repeated taps must not
duplicate launches. Device changes, background disconnect, deletion and dialog
dismissal cancel pending work and prevent stale writes/results.

## Validation

Use a local fake ADB daemon to check real frames, RSA token signatures/public key
format, fragmented output, rejection, malformed/oversized packets, cancellation,
and command exit status. Test real preference persistence, launch routing and
Compose setup interactions. Run the JVM suite and assemble the debug APK.
Physical FlowBox authorization and launch remain a hardware check.

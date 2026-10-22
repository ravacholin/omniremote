# Brightness shortcut

The D-pad's former Source button is now Brightness (sun icon).

On Android TV, add the installed TVDimmer app in **Apps** for that TV, using its
actual Android package name. Name the entry **TVDimmer** or **TV Dimmer** (a subtitle
such as Night Companion is also accepted). The brightness button uses that entry
regardless of the current Apps search or favorites filter. No package name is
assumed until the exact app listing is confirmed.

The shortcut sends an app-link launch request over the paired Android TV Remote
Service v2 connection. A sent request is not confirmation that the TV opened the
app: TVDimmer must be installed and the TV must support app-link launches. There
is no ADB or developer-mode requirement for this shortcut. Connection or setup
problems appear in the existing command feedback bar.

On Samsung Tizen the action is deferred: the button explains that the shortcut
is not configured and points to Picture settings. No model-specific menu key
sequence or unverified settings app identifier is sent.

## Validation on hardware

1. Connect to an Android TV with TVDimmer installed and its entry saved in Apps.
2. Tap Brightness and check that TVDimmer opens on the TV, including after reconnecting.
3. Check the sun icon and Brightness accessibility label on small and large screens.
4. Remove the app entry and tap Brightness; check the setup guidance.
5. Select a Samsung TV and check the deferred-action message.

Automated TLS session tests validate the app-link frame alongside a keypress and
a keepalive response. They do not confirm installation or launch on a physical TV.

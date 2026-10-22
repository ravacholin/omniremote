# Brightness shortcut

The D-pad's former Source button is now Brightness (sun icon).

On Android TV, **TvDimmer - NightCompanion** is included under **Tools** with its
verified package name `com.tribalapps.tvdimmer`. Database version 3 adds this entry
once to saved Android TVs, preserving existing shortcuts and custom dimmer entries.
The brightness button uses the saved TVDimmer entry regardless of the current Apps
search or favorites filter. TVDimmer must still be installed on the TV.

The shortcut sends an app-link launch request over the paired Android TV Remote
Service v2 connection. A sent request is not confirmation that the TV opened the
app: TVDimmer must be installed and the TV must support app-link launches. There
is no ADB or developer-mode requirement for this shortcut. Connection or setup
problems appear in the existing command feedback bar.

On Samsung Tizen the same button is labeled **Bluetooth** and sends a normal-menu
navigation sequence targeting the user's **UN50RU7100GCFV (2019)**:
Settings > Sound > Sound Output > Bluetooth Speaker List. It leaves selection and
pairing to the user. The sequence takes several seconds, ignores repeated taps
while running, and cancels on manual keys, text entry, app launch, or device switch.
The remote protocol cannot inspect focus. This sequence has not been validated on
physical hardware and may need adjustment for menu layout or timing differences.

## Validation on hardware

1. Connect to an Android TV with TVDimmer installed and its entry saved in Apps.
2. Tap Brightness and check that TVDimmer opens on the TV, including after reconnecting.
3. Check the sun icon and Brightness accessibility label on small and large screens.
4. Remove the app entry and tap Brightness; check the setup guidance.
5. Select the Samsung RU7100, tap Bluetooth, and check that the speaker list opens.
6. Verify that Back or switching TVs cancels an in-progress Samsung sequence.

Automated TLS session tests validate the app-link frame alongside a keypress and
a keepalive response. They do not confirm installation or launch on a physical TV.

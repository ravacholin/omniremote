# Brightness shortcut

The D-pad's former Source button is now Brightness (sun icon).

On Android TV, **TvDimmer - NightCompanion** is included under **Tools** with its
verified package name `com.tribalapps.tvdimmer`. Database version 3 adds this entry
once to saved Android TVs, preserving existing shortcuts and custom dimmer entries.
The brightness button uses the saved TVDimmer entry regardless of the current Apps
search or favorites filter. TVDimmer must still be installed on the TV.

Brightness and the Apps grid now share the same serialized launch path over the
paired Android TV Remote Service v2 connection. Neither sends the unregistered
`android-app://com.tribalapps.tvdimmer` or `market://launch` URI. Without a verified
TVDimmer deep link, both open its official Google Play page and show
«TvDimmer - NightCompanion: selecciona Abrir en la pantalla de la TV».
Select Open on the TV to launch the installed app. No Enter key is sent automatically:
the page may show Install instead. Explicit custom deep links are preserved.
There is no ADB or developer-mode requirement. Sending a request does not confirm
that the TV resolved it or opened TVDimmer; physical FlowBox testing is still required.

On Samsung Tizen the same button is labeled **Bluetooth** and sends a normal-menu
navigation sequence targeting the user's **UN50RU7100GCFV (2019)**:
Settings > Sound > Sound Output > Bluetooth Speaker List. It leaves selection and
pairing to the user. The sequence takes several seconds, ignores repeated taps
while running, and cancels on manual keys, text entry, app launch, or device switch.
The remote protocol cannot inspect focus. This sequence has not been validated on
physical hardware and may need adjustment for menu layout or timing differences.

## Validation on hardware

1. Connect to an Android TV with TVDimmer installed and its entry saved in Apps.
2. Tap Brightness, select Open on its Google Play page and check that TVDimmer opens, including after reconnecting.
3. Check the sun icon and Brightness accessibility label on small and large screens.
4. Remove the app entry and tap Brightness; check the setup guidance.
5. Select the Samsung RU7100, tap Bluetooth, and check that the speaker list opens.
6. Verify that Back or switching TVs cancels an in-progress Samsung sequence.

Automated TLS session tests validate the app-link frame alongside a keypress and
a keepalive response. They do not confirm installation or launch on a physical TV.

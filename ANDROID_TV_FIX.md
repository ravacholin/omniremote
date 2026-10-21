# Android TV detection and pairing repair

Target reported by the user: FlowBox-Z3 running Android TV OS 14. The Sony panel is the display; remote pairing targets the FlowBox on the local network.

The connection now uses Android TV Remote Service v2 (mutual TLS on 6467 for pairing and 6466 for control). Discovery probes both ports before optional Cast/Samsung metadata, reports subnet discoveries as they arrive, and avoids identifying arbitrary web/ADB servers as TVs. Wi-Fi/Ethernet link addresses take priority over unrelated interfaces when choosing the subnet.

Pairing checks the status and expected message at every step, validates the six hexadecimal characters and checksum, and preserves the exact client identity between certificate lookup and TLS creation. Certificates use GeneralizedTime after 2049 and expired persisted certificates are regenerated. An affected existing installation may therefore need to pair again once.

The control session follows the TV's configure → active → start exchange, negotiates implemented features, answers ping requests and serializes frame writes. The app shows Connected only after RemoteStart. Cancellation/retry closes pending pairing sessions; failed connections and failed key writes no longer return unconditional success.

## Automated validation

Run from the checkout:

```sh
source /workspace/.tooling/env.sh
gradle :app:testDebugUnitTest :app:assembleDebug --offline --console=plain
```

`AndroidTvRegressionTest` reproduces the original four failures: expired certificate, incorrect RemoteDeviceInfo fields, invalid PIN acceptance, and optimistic connection success. `AndroidTvSessionTest` exercises detection with only the pairing port, rejects false TV matches and invalid protocol messages, and runs pairing, remote negotiation, ping response and key injection against a local mutual-TLS server.

Protocol reference: https://github.com/tronikos/androidtvremote2 (polo and remotemessage schemas and pairing/remote implementations).

Validation result: 20 tests passed, 0 failures, 0 errors, 0 skipped. Debug APK assembled successfully. The TLS integration fixture requires java.net access for Conscrypt under JDK 21; this is configured only for JVM tests.

## Physical verification still required

This environment has no access to the user's FlowBox or home LAN. Install the debug APK on the phone, keep the phone and FlowBox on the same LAN, select the discovered box, enter the six-character code shown by the FlowBox, and check navigation after leaving the connection idle for at least 30 seconds. Reopen the app to check persisted authorization. If multicast is filtered by the router, use the subnet scan or the IP shown in the FlowBox's Settings → About → Status. Android TV Remote Service must be enabled on the box.

## APK download

[Download the verified debug APK](https://github.com/ravacholin/omniremote/raw/refs/heads/downloads/android-tv-pairing-fix/OmniRemote-AndroidTV.apk).

SHA-256: `89591c3858f025eea33e8232dd248dcae0fa46b614c60eccb1a376b0b9a48b31`.

The APK is stored on the repository's `downloads/android-tv-pairing-fix` branch for direct download on Android. Install on the phone; physical FlowBox verification is still required.

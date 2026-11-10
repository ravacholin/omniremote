# ADB Brightness Implementation Plan

**Goal:** Open TVDimmer directly from OmniRemote using authorized ADB over TCP.
**Architecture:** Separate TCP client and persistent RSA identity, per-device
settings, controller coordinator with setup state, and a Compose setup dialog.
**Tech stack:** Existing Kotlin, coroutines, JCA, SharedPreferences, Compose.
**Spec:** `docs/superpowers/specs/2026-11-10-adb-brightness-design.md`

## Constraints and review focus

- No dependency or database migration; use the existing isolated checkout.
- Bound untrusted frames/output and authorization wait; cancellation closes TCP.
- Never open Play Store on this path or claim success from an unconfirmed write.
- Switching device, background, deletion and dismissal invalidate pending work.
- Preserve regular Remote v2 and Samsung behavior; explain TCP versus TLS setup.

## Task 1: Authenticated ADB transport

- [x] Write and run failing local-daemon tests for `AdbTcpClient.launch(host,
  port, packageName)` and `AdbIdentity.load(context)` persistence/signing.
- [x] Implement RSA identity and framed CNXN/AUTH/OPEN/OKAY/WRTE/CLSE transport.
- [x] Verify command status, bounds, validation, cancellation and failures.

## Task 2: Settings and launch coordination

- [x] Write failing tests for `AndroidAdbBrightness` state and saved port behavior.
- [x] Implement optional per-device TCP port storage and setup/retry/forget flow.
- [x] Route Android Brightness and the TVDimmer app-grid entry through ADB.
- [x] Cancel work on target/lifecycle changes; clear settings on deletion.

## Task 3: Setup UI and delivery

- [x] Test setup port validation, busy/cancel behavior and submit interactions.
- [x] Add setup dialog and saved-device configuration entry, wire ViewModel.
- [x] Update brightness documentation with exact setup and hardware limitations.
- [x] Run full JVM suite, assemble APK, inspect results, review complete diff.

## Execution ledger

Authorization: user explicitly chose ADB and requested implementation; proceed
without another design/plan approval. Native implementation in this session.
Baseline initially blocked by missing metadata in a Gradle transform cache;
quarantined that cache entry and reran. No source changes for environment repair.

Implementation progress: transport, coordinator, routing and setup UI implemented.
The initial targeted run completed 17 tests: 16 passed and the restart identity
test failed because Android Conscrypt imports PKCS#8 keys without exposing CRT
interfaces. Fixed by explicitly generating exponent F4 and rebuilding the public
key from RSAPrivateKey.modulus and F4; restart/signature test retained.

Independent review (review_adb_brightness): no Critical transport issue; two
Important fixes required: configuration must use the saved dimmer package, and
device-card actions must wrap at narrow widths/larger font scales.

A later overlapping Gradle test/compile attempt invalidated live test class
files; those ClassNotFound failures are infrastructure, not accepted validation.
All subsequent final verification will run sequentially on stable sources.

Review fixes: the ViewModel now uses the same saved-app lookup for the device
card and Brightness, verified by a new real Room/ViewModel regression test that
failed with the default package before the fix. Device actions use a weighted
FlowRow; a 280dp card with 1.4x font scale exercises actual touch input on ADB,
pairing and deletion. Initial normal-width layout test already passed; wrapping
is a defensive layout improvement, not a claimed reproduced normal-width failure.
Added verification of the ADB public key Montgomery parameters, rejected AUTH,
wrong stream IDs and damaged checksums. Final suite/build running sequentially.

Final verification: `source /workspace/.tooling/env.sh && gradle --offline
--console=plain :app:testDebugUnitTest :app:assembleDebug` succeeded. 57 XML
reports: 350 tests, 0 failures, 0 errors, 0 skipped; 21 tests cover this feature.
APK version 1.8-adb-brightness, versionCode 9 retained for fallback compatibility,
applicationId com.aistudio.omnitvremote.qvrtm. Signature verified with apksigner.
APK SHA-256: 047b4273f0284a3715f5cef6385ec5a888476989a5921a00ea13348b39922d6f.
`git diff --check` passed. No attached devices (`adb devices` empty): physical
TCP availability, authorization and foreground launch remain unverified.

Review fixes verified by the final suite; no remaining Critical/Important
findings. Preserve the feature branch and workspace for user testing; no merge
or publication is part of this implementation request.

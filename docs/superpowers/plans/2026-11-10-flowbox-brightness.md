# FlowBox brightness completion

The user confirmed twice on their physical FlowBox that entering TVDimmer's Play
Store listing initially focuses Open, and one OK from OmniRemote launches the app.
Developer options are unavailable. Default brightness must therefore use the
existing Remote v2 session to open the listing and send one delayed OK. ADB remains
an explicitly selected advanced configuration only, never required by Brightness.

Implementation: a cancellable single-flight coordinator sends the store request,
waits two seconds after it is accepted without immediate rejection, then sends one
ENTER. Both the store request and ENTER check cancellation/target validity inside
the client's socket write lock. A navigation generation captured before the Apps
lookup prevents delayed database results from restarting cancelled navigation.
Repeated brightness taps are ignored. Manual keys, text, other app launches,
device changes, disconnection and command rejection cancel pending automation.
Apply the same path to the TVDimmer Apps entry. Do not report physical launch as
confirmed: Remote v2 has no positive foreground acknowledgement.

Validation: virtual-time tests for ordering, delay, single-flight, rejection,
cancellation, changed targets and final write guards; existing routing test updated
to require no ADB dialog by default. Run full JVM suite and assemble a signed debug
APK. Upload the corrected version to the user's Drive, retaining earlier files.

Hardware basis: user-verified Open focus and OK behavior. Automatic timing still
needs testing on the FlowBox; Play Store focus changes (e.g. an Update action) can
invalidate this device-specific navigation assumption.

Review: independent code review identified the pending database lookup and initial
store-write cancellation windows. Both were corrected and re-reviewed successfully.
Regression checks cover stale lookup generations and a denied final store write.

Final validation: `:app:testDebugUnitTest :app:assembleDebug` succeeded. All 355
JVM/Robolectric tests passed with zero failures, errors or skips. APK metadata is
version 1.9-flowbox-brightness, code 9; APK v2 signature verifies. Size: 23,983,268
bytes. SHA-256: 182cb693fd81024f453c22294e5c6585a5dfd9c0b249b08b98c5b13eac439fb1.
No physical FlowBox was available for automatic timing validation.

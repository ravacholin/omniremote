# Sleep Timer Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan inline, with test-first checks.

**Goal:** Add 30 → 60 → 120 → OFF Sleep with full-duration restarts and targeted TV power-off.

**Architecture:** Persist a single timer and schedule an exact elapsed-realtime alarm. A short foreground service resolves the saved TV by ID and uses isolated protocol clients to send power-off without changing the interactive controller.

**Tech Stack:** Kotlin, Compose, AlarmManager, SharedPreferences, coroutines, Robolectric/JUnit.

**Spec:** ../specs/2026-10-29-sleep-timer-design.md

## Global Constraints

- Android minSdk 24, targetSdk 36. No new dependencies.
- Reuse saved authentication; never use POWER toggle or WoL for expiry.
- One timer, retained across app recreation; boot clears it.
- Use the existing isolated checkout and Android toolchain described in /workspace/.tooling/START.md.

## Review Focus

- Old/duplicate alarms after restart or OFF must do nothing.
- Changing the selected TV must not redirect an existing alarm.
- Permission refusal must leave no false active timer.
- Unreachable/deleted TVs must not trigger wake or another TV's command.
- Lock screen and app process death require OS scheduling, independent of ViewModel.

### Task 1: Persist and schedule Sleep

**Files:** `sleep/SleepTimerManager.kt`, `SleepTimerTest.kt`, `data/model/RemoteKey.kt`.

**Interfaces:** `SleepTimerManager.advance(device): SleepTimerState?`, `cancel()`, `cancelForDevice(id)`, `consumeDue(token): SleepTimerState?`, `timer: StateFlow<SleepTimerState?>`.

- [x] Write tests for cycle, restarted deadlines, persistent target, stale/duplicate alarms, cancellation, permissions, deletion, boot and explicit power-off codes; run and observe missing-feature failures.
- [x] Implement exact wakeup alarms, persisted state and token validation. Change Android POWER_OFF to KEYCODE_SLEEP (223).
- [x] Run SleepTimerTest; initial 10 cases passed in the combined targeted run.

### Task 2: Execute power-off in the background

**Files:** `sleep/TvSleepPowerOff.kt`, `service/SleepTimerService.kt`, `AndroidManifest.xml`, backup rules; `SleepPowerOffTest.kt`.

**Interfaces:** `TvSleepPowerOff.powerOff(device): Boolean`; alarm service consumes a due token, resolves the device and sends only POWER_OFF.

- [x] Add a protocol test with a local TLS TV and verify sleep key 223; test refusal for Mac. Observe failures before implementing.
- [x] Add bounded connection/send execution, a short foreground service, wake lock, notification/result feedback, and manifest declarations. Exclude ephemeral timer state from backup.
- [x] Run targeted tests and build; passed in the complete verification below.

### Task 3: Button, notice and permission flow

**Files:** `ui/components/SleepTimerButton.kt`, `ui/components/DeviceSelectorBar.kt`, `ui/MainScreen.kt`, `ui/MainViewModel.kt`, `SleepTimerButtonTest.kt`, `docs/SLEEP.md`.

**Interfaces:** ViewModel exposes timer/result flows, cycle and cancellation; Compose shows Sleep value and target and invokes Android's exact-alarm settings only when necessary.

- [x] Add Compose tests for a reachable button, value cycling and Mac exclusion; observe missing-feature failures.
- [x] Wire button, transient confirmation, active-target/cancel notice, permission result and deletion cancellation. Document behavior and manual device checks.
- [x] Run `gradle --console=plain --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`; inspect test counts, APK and lint report.
- [x] Review changes for stale deliveries, target isolation and background lifecycle; resolve material findings and rerun affected checks.

## Execution notes

The 17 initial Sleep tests passed. The baseline and targeted runs both reproduced
`DeviceDeletionTest.deleting the final active TV disconnects the remote`: an older
device-list emission could select a TV after deletion. The ViewModel now rejects
selection of IDs deleted during its lifetime; the existing regression test covers it.

An independent read-only review identified two important issues: cancellation while
an expiry is connecting and Samsung connections without saved authorization. The
fix retains an in-memory execution claim while the live service owns the connection,
invalidates it on advance/cancel/deletion, and checks it at the actual Android key
write (including after implicit reconnection) or immediately before the Samsung send.
The claim cannot outlive its service process. Samsung refuses blank tokens before
connecting. New tests cover the three invalidations over gated TLS connections and
refusal to connect without credentials. Completion stops the service unconditionally
so a duplicate start cannot leave it running with an obsolete startId.

Implementation decisions: one global timer; a new setting targets the current TV;
boot cancels Sleep; no new runtime notification permission is requested because the
foreground notification is temporary and the outcome is retained in the app. There
is no emulator or physical TV attached, so JVM evidence does not establish actual TV
power or OEM lock-screen behavior.

Final verification: `BUILD SUCCESSFUL`, 132 tests across 27 classes, no failures,
errors or skips. Includes 20 Sleep tests and both existing DeviceDeletion tests.
Lint: 0 errors, 52 warnings (including synchronous preference persistence, an
application-context singleton and KTX/style suggestions in the new code). APK:
`app/build/outputs/apk/debug/app-debug.apk`. `git diff --check` passed. No deferred
review findings.

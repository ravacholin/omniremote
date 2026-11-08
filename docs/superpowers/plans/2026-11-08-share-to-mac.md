# Share to Mac Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline.

**Goal:** Compartir enlaces al Mac y abrir una cola persistente en orden.
**Architecture:** Parser de intents y URLs; cola Room por Mac; transporte SSH existente con guardia de generación; diálogo y lista Compose en MainScreen.
**Tech Stack:** Kotlin, Android intents, Compose Material3, Room, JSch, Robolectric.
**Spec:** docs/superpowers/specs/2026-11-08-share-to-mac-design.md

## Global Constraints

- Sin dependencias nuevas ni cambios en credenciales SSH.
- Guardar desconectado, abrir solo con conexión al Mac elegido.
- «Abrir siguiente» manual FIFO, sin reproducción automática.
- Migración conservadora de versión 3 a 4 y borrado en cascada por Mac.

## Review Focus

- Texto compartido con título y URL, mapas, esquemas peligrosos y controles.
- Rotación y nuevos intents no deben duplicar envíos.
- Error SSH no debe eliminar enlaces; pulsaciones repetidas no deben enviarlos dos veces.
- Cambio/borrado de dispositivo no puede enviar a otro Mac o TV.
- Actualizar la app conserva equipos, tokens y apps existentes.

### Task 1: Input, persistence and safe opening

**Files:** new share/SharedMacLink.kt, data/model/MacLinkEntity.kt, data/local/MacLinkDao.kt, share/MacLinkQueue.kt; AppDatabase.kt, MacScripts.kt, UnifiedTvController.kt; new MacLinkTest.kt.
**Interfaces:** SharedMacLink.fromIntent/parse; DAO observe/get/first/insert/delete; MacLinkQueue.enqueue/openNext(deviceId, suspend opener); controller.openMacLink(deviceId, url): MacResult.

- [x] Write and run failing parser, Room persistence/isolation/deletion/migration, FIFO failure/concurrency and hostile URL tests.
- [x] Implement validated URLs, migration, FIFO coordinator and SSH opening via stdin.
- [x] Run targeted tests, including real script execution with a controlled JXA boundary and SSH fixture.

### Task 2: Share and queue UI

**Files:** MainActivity.kt, MainViewModel.kt, MainScreen.kt, AndroidManifest.xml; new ui/components/MacLinkDialogs.kt, MacShareUiTest.kt.
**Interfaces:** receive shared content in Activity; pendingShare in VM; save/open shared and open next; reusable share and queue dialogs with explicit target IDs.

- [x] Write failing Compose tests for offline save, target selection, invalid input, busy/connection gating and FIFO action.
- [x] Implement share registration, lifecycle-safe intent receipt, dialog and queue access in Mac panel.
- [x] Verify new UI tests and existing Mac/Activity regressions.

### Task 3: Full verification and documentation

**Files:** docs/MAC.md, spec/plan and fixes if needed.

- [x] Run full :app:testDebugUnitTest :app:lintDebug :app:assembleDebug.
- [x] Review diff against requirements; resolve material findings and verify affected paths.
- [x] Prepare installable debug APK and report physical-device limits.

## Verification and review log

- Existing Gradle cache had one incomplete immutable transform (missing metadata.bin). Removed only that generated cache entry; offline build configuration resumed.
- Initial parser/Room suite: 4 tests passed (persistence, per-Mac isolation, FIFO, failure retention, simultaneous taps).
- Added SSH integration using the existing FakeMac server, plus a blocked-channel fixture proving generation cancellation prevents transmission.
- Ran the generated JXA body under Node with a controlled Objective-C boundary: hostile URL round-trip and rejected macOS open both passed. Physical macOS API execution remains unverified.
- Independent read-only review found prose punctuation, geo address placeholders and a deletion cancellation window. Added regression fixtures; observed punctuation and immediate deletion tests fail before their fixes. The parser now preserves balanced parentheses, trims prose wrappers, handles query coordinates/labels and searches address-only geo shares. Active Mac deletion now disconnects synchronously before Room work.
- Retained-Activity snapshot revival and old queue result leakage: both regressions observed failing. Added one-time state restoration and closed the old queue on new shares.
- Lifecycle test initially attached the app's infinite animation to Robolectric's uncontrolled clock; ActivityController.recreate also assumed a non-null focused window root. Thread dumps and failure stacks isolated those harness assumptions. The final fixture uses real pause/save/stop/destroy/create(savedState) callbacks without attaching the animated screen; a separate regression covers retained-model restoration. Dialog interactions use clock-controlled Compose tests and scroll to controls inside the dialog before touching them.
- Final required command: `gradle --offline --console=plain --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` (result recorded after completion).
- No Android hardware/emulator is attached (`adb devices` empty); the Mac/browser physical flow requires device verification.

- Full initial check: 300 tests executed, 298 passed; the two new UI/lifecycle fixture failures were isolated to an offscreen connection button and Robolectric window-focus assumptions. Lint completed with 0 errors and 90 warnings. Final rerun recorded below.

## Final result

`gradle --offline --console=plain --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → **BUILD SUCCESSFUL** (2m 12s).

**300 tests in 49 suites, 0 failures, 0 errors, 0 skipped.** Lint: **0 errors, 90 warnings**.
All independent review findings are resolved and covered; no deferred findings.

Installable debug APK: `/workspace/shared/downloads/OmniRemote-Compartir-Mac.apk`.
APK v2 signature verified; feature classes and the NSWorkspace opening script verified in DEX.
SHA-256: `b40d661ed4322e2c26575528d86b1799b2c0c7f3b22cafdfb63b59d4900d960d`.
Physical Android/Mac interaction is not claimed as tested.

## Integration with current main / parallel PR #45

Rebased on `3c8e2af4106646ca7446f9e2d0753af1bbd0f257`, which merges PR #45 (Mac media app/tab selection and bounded/cancellable SSH). Resolved both textual conflicts by retaining the complete SSH test fixture, exposing its shared helpers to link tests, and preserving both Mac documentation sections. Media controller, notification routing, JavaScript resources and SSH timeout/watchdog implementation remain unchanged from main.

Added a real SSH session integration fixture: discover and pin Spotify, open a queued link without changing its selection or sending media actions, send one directed play/pause, then read volume on that same connection. Its first run exposed the paused Robolectric clock at the coordinator's 150 ms delayed observation; advancing the simulated Android clock verifies completion without changing production timing.

Independent integration review: no material findings. Final command:
`gradle --offline --console=plain --no-daemon -Pksp.incremental=false :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
→ **BUILD SUCCESSFUL** (2m 11s), **319 tests across 52 suites, zero failures/errors/skips**; lint **0 errors, 90 warnings**.
`node --test scripts/mac-media.test.cjs` → **13/13 passed**.

Combined APK: `/workspace/shared/downloads/OmniRemote-Compartir-Mac-main.apk`.
Both media JS resources match source byte-for-byte; DEX includes both features.
SHA-256: `16b604ce967f71ace145c438b7d0c5c08f655c09406af49eff4da68207d6bd17`. Physical Android/Mac validation remains pending.

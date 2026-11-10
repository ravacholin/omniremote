# Reliability audit implementation plan

> Execute inline using the existing checkout. The user authorized investigation, fixes and optimization.

**Goal:** Fix reproduced failures in existing functions, preserving saved devices and credentials.

**Architecture:** Keep existing Room repositories and transport clients. Bind asynchronous commands to their original target and update favorite state atomically in Room.

**Tech stack:** Kotlin, coroutines, Room, Robolectric, local TLS/SSH servers, Node tests.

- [x] Establish baseline: JVM tests, Node tests, lint and debug APK.
- [x] Reproduce rapid favorite toggles against a real Room database; replace snapshot-based updates with an atomic SQL toggle; verify retained metadata.
- [x] Reproduce app commands from a stale device grid and text waiting across a target change; apply the existing command-generation guard at dispatch and transport-write boundaries; verify normal commands still work.
- [x] Reproduce cancelled Mac authorization persisting credentials; prevent cancelled login from restoring saved authorization; verify successful password/key login.
- [x] Reproduce Samsung's text action reporting success without a transport write; route it to the existing authorized WebSocket using the Samsung input-string command and verify Unicode and failed sends.
- [x] Inspect remaining lint and test failures, correct actionable defects and rerun the full suite and build.
- [x] Review the final diff and document verified behavior and hardware limitations.

Constraints: preserve signing configuration, application identity, database compatibility and existing transport behavior. No new dependencies or UI redesign. No connected Android device is available; physical TV/Mac behavior must remain explicitly unverified.

## Verification ledger

- Node baseline: 17 passed. Original JVM suite plus new initial regressions: 359 executed, only the 3 new regressions failed; simultaneous targeted execution invalidated the full report output, so the final full run must be sequential.
- Red: favorites toggle, text session replacement and in-flight Mac authorization failed before their fixes.
- Partial: favorite and Mac fixes passed; stale app-grid dispatch, text session replacement and app-link session replacement failed.
- Green: targeted regressions plus existing Mac, Android text and app-launch tests passed (`/tmp/omniremote-regressions-green.log`, Gradle exit 0).
- Independent code review: no introduced regression found. Related preexisting gap: Mac login queued before disconnect could start afterwards. Add a failing queued-login test and carry target validity from controller dispatch through lock acquisition and credential persistence.
- Review test improvements: replace the fixed app-dispatch wait with child-job completion; cover controller generation invalidation with an unchanged socket.
- The reviewer excluded final build/lint status and physical hardware checks because they had not run. Final verification remains required; hardware remains unavailable.
- Review red: queued Mac password login failed before the follow-up fix; the controller-generation test and deterministic stale-grid test passed. Generation is now captured before the SSH operation lock and controller validity is checked before login and credential persistence.
- Follow-up verification: queued Mac login and controller-generation regressions passed; only the new Samsung text regression failed (`/tmp/omniremote-samsung-red.log`, 20 tests, 1 failure).
- Samsung fix: input now uses `SendInputString` with UTF-8/Base64 on the authorized socket; empty/unauthorized input and rejected writes are covered. Final complete JVM suite, APK and lint run is in progress.
- Final Node verification: 17 tests passed, zero skipped, zero failures.
- Final full verification: normal project command `gradle --offline --console=plain --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` exited 0 (`BUILD SUCCESSFUL`). XML reports confirm 365 JVM/Robolectric tests in 60 classes, zero failures/errors/skips; lint has zero errors and 78 warnings. APK: 23,984,794 bytes. `git diff --check` passed.
- Final review: independent review completed; its queued-login finding was reproduced RED, corrected and verified GREEN in the full suite. Its two test weaknesses were addressed. Hardware checks remain unavailable; this is recorded in `docs/AUDITORIA_FIABILIDAD_2026-11-10.md`.
- PR integration: rebased onto `main` at `9ea372a` (#51). Preserved its Mac login/state-lock implementation and endpoint guards, which overlap the Mac fixes above; retained this audit's additional login regressions. Removed the duplicate test authorization hook during conflict resolution.
- Integrated verification: Gradle build, full JVM/Robolectric suite and lint exited 0. Reports: **385 tests**, 67 classes, no failures/errors/skips; **17 Node tests** pass; lint has zero errors and 78 warnings. Rebuilt APK is 24,385,582 bytes, SHA-256 `22e13782364938fd5e71fe4b81d173a1806cf0dbd40570ccd8c5330a865f4987`; uploaded to the user's Drive and verified by metadata readback.

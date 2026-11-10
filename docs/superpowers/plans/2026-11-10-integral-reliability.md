# OmniRemote reliability implementation plan

> Execute inline with superpowers:executing-plans and test-driven-development.

Goal: implement the approved conversation plan while preserving the Mac content queue, current UI, saved devices, and authorization methods.
Architecture: small internal changes in the existing controller, SSH client, scan coordination, and NSD merge. No database migrations or dependency upgrades.
Spec: approved conversation plan, including explicit preservation of the Mac content queue.

## Constraints and review focus
- Work in the existing isolated /workspace/omniremote checkout (environment START.md).
- Do not change MacLinkQueue's ordering, persistence or successful-open removal semantics.
- Review generation guards across device changes, reconnects, cancellation and deleted-device credential cleanup.
- Preserve verified discovery metadata in either arrival order; retain protocol-specific ports.
- Keep cancellation distinct from network failure, release network resources and restore UI state.
- Do not replace durable synchronous credential/timer writes with asynchronous apply merely to silence lint.

## Task 1: Mac volume destination
- [x] Add deterministic controller regression using an injected IO dispatcher: enqueue on Mac A, switch to B before consumption, verify no command reaches B; verify same-Mac coalescing still sends the latest value.
- [x] Run failing tests, then carry TvDeviceEntity and command generation with the conflated volume request and validate both before sending and publishing results.
- [x] Run Mac controller/link/media tests.

## Task 2: Mac login lifecycle
- [x] Add SSH integration regressions for disconnect during authorization and failed credential storage; verify no saved credentials/no retained session.
- [x] Serialize disconnect with the final credential/session publication using a short state lock, guard before authorization/persistence, and close provisional sessions in finally.
- [x] Carry caller cancellation/generation through controller login; guard repeated submissions and obsolete dialog completion in MainViewModel.
- [x] Run login and Mac link/media regressions.

## Task 3: Discovery lifecycle and merge
- [x] Add regressions for malformed subnet/retry, cancellation between probes, and metadata preservation in either NSD/probe arrival order.
- [x] Validate IPv4 /24 prefixes before network access; check cancellation between probes and propagate CancellationException; expose a cancellable scan job in the model with try/catch/finally and close-screen cancellation.
- [x] Merge nonblank MAC/model and authoritative remote ports without replacing a known Mac with another protocol.
- [x] Run discovery/diagnosis regressions.

## Task 4: Whole-function review and validation
- [x] Add persistent content queue regression across database reopening, connection/device changes and failed opening. Leave queue implementation unchanged unless a demonstrated defect requires correction.
- [x] Review transport/service/timer control paths and run all JVM tests, JS tests, lint and debug build.
- [x] Fresh final review; fix important findings with reproductions. Document hardware test steps and limitations.

Commands: source /workspace/.tooling/env.sh before gradle; gradle --console=plain --no-daemon :app:testDebugUnitTest [--tests ...]; final :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; node --test scripts/mac-media.test.cjs.
Expected: targeted regressions fail on original behavior then pass; final build success, no failed/skipped tests and no lint errors.

## Completion

Implemented and verified on 2026-11-10. Final Gradle assembleDebug/testDebugUnitTest/lintDebug: BUILD SUCCESSFUL. JVM/Robolectric: 375 tests, zero failures/errors/skips. JavaScript: 17 tests, zero failures/skips. Lint: zero errors, 78 pre-existing warnings. Debug APK signature verified. No device was attached for physical testing.

The final independent review identified two additional endpoint/lifecycle races. Both were reproduced before fixing: re-saving the same Mac with a changed SSH port now reconnects and invalidates pending actions; NSD/fallback callbacks cannot publish or alter resolver state across stop/restart. Final targeted verification passed before the complete suite.

Defaults retained: existing isolated checkout; inline implementation and one final independent review; unchanged UI, dependencies, database schema, content queue and fixed TV protocol ports. Publishing is a separately authorized follow-up; merging is not part of this change. A concurrency flaw in the cancellation test fixture was corrected by waiting for cancellation propagation before asserting; application behavior was unchanged by that test correction.

# Mac media targets implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task by task. Steps use checkbox syntax for tracking.

**Goal:** Select a specific Mac app or compatible Chrome media tab from OmniRemote and its shared notification session.

**Architecture:** Keep SSH as the only transport. A Mac media coordinator owns selection/revisions and executes bounded discovery and directed scripts through the existing controller. Browser adapters use DOM/native media methods in Chrome's isolated AppleScript world.

**Tech Stack:** Kotlin, Compose, coroutines/StateFlow, Moshi, JSch, AppleScript/JXA, JavaScript. No added runtime dependency.

**Spec:** `docs/superpowers/specs/2026-11-08-mac-media-targets-design.md` (approved by user on 2026-11-08).

## Global Constraints

- Apps: Spotify/Music. Chrome: YouTube, YouTube Music, Spotify web. No extension, agent, OAuth or new permissions changed automatically.
- Manual selection never falls back; volume/mute remain Mac output controls. Automatic preserves the existing heuristic.
- Exclude incognito and unrelated hosts; filter before returning tab data. Do not launch applications while discovering.
- Keep tab identity through reordering/moving and same-service track changes; reject stale commands/results and old notification media buttons.
- No background polling, no automatic command replay. Honest unknown/unverified state and per-source errors.
- Real macOS/site validation cannot be claimed from Linux tests. Ship a manual validation checklist recording this limitation.

## Review Focus

- Two equal-titled tabs must remain separately selectable after reordering (browser harness and model fixtures).
- A same-tab navigation during an action must reject the action without redirecting it (browser harness).
- A source permission failure must not hide healthy sources or break volume (protocol/coordinator tests).
- A blocked SSH read must release the channel and permit a subsequent volume read (SSH integration test).
- A pending notification after source/device change must not control the new target (notification/session tests).

## File structure and interfaces

- `network/MacMedia.kt`: source/capability/status/snapshot JSON protocol and validation.
- `network/MacMediaScripts.kt`, `main/resources/mac_media_{host,browser}.js`: discovery, inspect and directed action scripts.
- `controller/MacMediaController.kt`: `state: StateFlow<MacMediaState>`, `reset()`, `invalidate()`, `refresh()`, `select(id: String?)`, `send(key: RemoteKey): Boolean`; injectable suspending runner and generation guards.
- Existing `MacSshClient`: bounded `run(script, canSend)`; existing `UnifiedTvController`: `runMacMediaScript(script, canSend): MacResult`.
- `RemoteSession`: owns media controller and routes all Mac media actions; UI and notification consume its state/revision.
- `ui/components/MacMediaSelector.kt`: source picker, per-source/configuration errors; existing panel receives state and callbacks.

### Task 1: Protocol, adapters and bounded transport

**Files:** new network models/scripts/resources; `MacSshClient.kt`; `MacRemoteTest.kt`; new `MacMediaProtocolTest.kt`, `scripts/mac-media.test.cjs`.

- [x] Write failing protocol tests: filter malicious/unsupported URLs, preserve IDs, reject malformed snapshots, never mark unverified candidates controllable.
- [x] Write failing executable JS tests using Node's built-in test runner and a controlled DOM/host harness: paused media, unrelated routes, disabled next, stale document, separate tabs, no activation, permission failures.
- [x] Run `gradle :app:testDebugUnitTest --tests com.example.MacMediaProtocolTest` and `node --test scripts/mac-media.test.cjs`; confirm missing behavior.
- [x] Implement typed JSON protocol, JXA host discovery/inspection/action and browser DOM adapters. Stable source IDs include process ID; validate origin/content inside action. Limits: 40 compatible candidates, response size 512 KiB, script channel deadline 6 seconds. Discovery reads one candidate at a time when necessary.
- [x] Extend simulated SSH tests with a stalled script and a subsequent successful volume request; implement deadline covering input/output and cancellation by closing only the command channel. Drain stdout/stderr together to avoid pipe stalls.
- [x] Run protocol, JS and SSH tests; commit the tested transport/adapters.

### Task 2: Shared media selection and notification routing

**Files:** new `MacMediaController.kt`, `MacMediaControllerTest.kt`; existing `UnifiedTvController.kt`, `RemoteSession.kt`, notification files and tests.

- [x] Write failing coordinator tests with a delayed transport: manual selection sticks; missing source blocks actions; stale refresh/action results discarded; source revisions cancel queued actions; same-service content changes preserve selection; partial failures preserve other sources.
- [x] Implement state machine with an injectable suspending runner, a single discovery job, bounded requests and source revision guard. Selection is ephemeral and defaults to Automatic. Return true from `send` only when a Mac media key was consumed.
- [x] Add `runMacMediaScript` guarded by device/generation and cancellation. Route media through the session; no duplicate automatic dispatch. Validate selected source on reconnect/foreground; no discovery polling from service.
- [x] Write and run stale-notification test; add selection revision to media PendingIntent identity/validation, observe state to refresh labels/capabilities. Keep contents private and volume/power unaffected.
- [x] Run coordinator and quick-remote/session regressions; commit shared routing.

### Task 3: Selector UI, documentation and full verification

**Files:** new `MacMediaSelector.kt`, `MacMediaSelectorTest.kt`; existing panel, main screen/view model, shared key component (optional enabled parameter), `docs/MAC.md`.

- [x] Write failing Compose tests: Automatic entry, separate tab labels, selection without playing, capability-based disabled actions, blocked source/configuration message.
- [x] Add scrollable selector dialog and state/callback wiring. Source metadata stays in the app; the notification names only the service. Mark experimental web adapters as pending real-Mac validation.
- [x] Update Mac setup and per-service manual validation checklist; distinguish sent commands from observed status.
- [x] Run `node --test scripts/mac-media.test.cjs`, `gradle --console=plain --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. Inspect nonzero XML test counts, lint report and APK.
- [x] Obtain a fresh-context whole-change review, fix material findings, rerun relevant tests and full checks if changed.
- [x] Commit implementation and report APK path, automated results and real-Mac validation limitation. No deployment or push required.

## Execution record

Execution method: inline in the existing isolated cloud checkout, as directed by `/workspace/.tooling/START.md`. User approved implementation; routine implementation details do not require another approval. No manual worktree or extra plan-approval round.

Ruling: first technical check uses executable controlled browser/JXA fixtures because no Mac is connected. All five adapters will be implemented; none will be described as validated on the user's Mac. Real device validation remains explicitly pending, with no silent coverage reduction.

## Verification result

Completed in the existing checkout. All 297 JVM/Robolectric tests passed with zero failures/errors/skips; `assembleDebug` and `lintDebug` succeeded (2026-11-08). The separate Node browser/JXA harness passed 13/13 tests. The generated APK contains both JS resources byte-for-byte matching source. No Android device or Mac was attached, so physical/JXA service validation remains pending in `docs/MAC.md`.

Fresh-context review approved the corrected code with no outstanding Critical/Important findings. Regression coverage includes cross-service navigation, Chrome restart, short bounded layouts, stalled input/output, cancellation, partial permission errors and stale notification buttons. Playback is explicitly labeled with its last observation time.

Ruling: delivery is one cohesive implementation commit rather than separate intermediate task commits; source/service identity and shared UI/session interfaces were verified together. No push, merge or external publication was performed.

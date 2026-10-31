# Credential and Sleep reliability fixes

**Goal:** Fix reproducible paths that reuse another TV's Samsung token, rotate an existing Android identity, erase saved data, or lose Sleep outcomes.

**Architecture:** Keep the current protocols and storage formats. Resolve Samsung credentials with device identity at every connection boundary. Preserve Android's RSA key during certificate repair, and fail without replacing unreadable private credentials. Record interrupted Sleep execution without retrying power toggles.

**Constraints:** Work in the existing checkout. Preserve existing authorizations and database rows. No hardware success claims from simulated TV tests. No merge or deployment.

## Tasks

- [x] Samsung identity: add failing socket/store/Sleep regressions for explicit empty credentials and conflicting hardware; carry id/MAC through connections and reconnects, and prevent database fallback from bypassing ownership checks.
- [x] Android TLS: add failing tests for corrupt certificates, partial identities and contextless cache initialization; repair from the existing private key and prefer persisted identity when a context becomes available.
- [x] Sleep recovery: reproduce process loss after claiming an expiry; persist an execution marker and report interruption on recreation, clearing markers on cancellation/replacement/completion without resending POWER.
- [x] Database safety: demonstrate destructive fallback on an unsupported schema; fail closed and verify the original rows remain on disk. Existing version 2 → 3 migration must remain green.
- [x] Stale metadata: reproduce a MAC/metadata save restoring an old database token after `updateAuthToken`; preserve the authoritative saved token during ordinary device updates.
- [x] Device discovery: preserve stable hardware IDs through DHCP changes and keep a replacement TV/protocol from inheriting the previous occupant's row or credentials.
- [x] Run all JVM tests, debug build and lint; request an independent review and address material findings.

Each code change follows a failing regression test. Run targeted tests after each task and the complete suite at the end. Source of requirements: the user's audit/fix request and existing `docs/SLEEP.md` contract.

## Review focus

Legacy credentials without ownership metadata; DHCP address reuse; two simultaneous TV sessions renewing tokens; partially damaged persisted TLS identity; cancellation or process death during Sleep.

## Execution record

- Baseline: `:app:testDebugUnitTest` succeeds from cache; final verification will execute tests after changes.
- Unknown version-1 schema is absent from history: do not invent a migration that could damage data. Remove automatic destructive fallback instead.
- Samsung RED: 23 selected tests executed, 3 expected regression failures (explicit blank token, conflicting controller fallback, conflicting Sleep fallback).
- Storage/Sleep RED: 33 selected tests executed, 5 expected regression failures (corrupt certificate key rotation, corrupt private key rotation, ephemeral cache hiding persistence, destructive migration, lost Sleep outcome).
- Metadata RED: 13 selected tests, one expected stale-token overwrite failure.
- Independent read-only review: found sequence races, duplicate callback/MAC writes, and Android wrong-target risk. Three deterministic Samsung regressions failed; MAC and pinned-server-key Sleep regressions also failed before fixes.
- TLS verification caught a provider compatibility issue: Conscrypt imported keys do not necessarily implement RSAPrivateCrtKey. Use provider key specifications instead of a concrete interface cast.
- Android identity: record the remote server's public key by stable device ID after authenticated startup. A successful explicit PIN permits replacing that identity; old installations bootstrap on the next authenticated connection, with MAC checks where available.

- Final full verification: `gradle --console=plain --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` succeeded. XML reports: 172 tests, 0 failures/errors/skips across 31 suites; lint: 0 errors, 57 warnings; APK: 24,004,503 bytes. `git diff --check` passed.
- Final review findings addressed: concurrent Samsung writes and stale aliases/callbacks, Android server identity/MAC validation. Discovery identity and pairing-port independence regressions also failed before their fixes and passed in the final full suite.

- Publication verification: rebased onto origin/main b5ffd88, preserving the Remote v2 port fix and adapting the identity-test endpoints to 6466. Full build, 172 tests and lint passed again.

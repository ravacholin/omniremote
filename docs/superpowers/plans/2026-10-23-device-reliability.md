# Device reliability implementation plan

**Goal:** Keep saved TVs, the default TV and the active remote consistent.
**Architecture:** Room transactions own device save/default/delete consistency; the repository remains the entry point. MainViewModel disconnects when its last active device is removed.
**Tech stack:** Kotlin, Room, coroutines, JUnit, Robolectric.

- [x] Reproduce default duplication, loss of default on rediscovery, whitespace duplicates, invalid default selection, and deletion fallback with an in-memory Room database.
- [x] Make device saves, default selection and deletion transactional. Normalize saved IP addresses, preserve existing credentials and default status, and leave exactly one default when devices remain.
- [x] Reproduce deleting the active device in MainViewModel, then disconnect the controller when no replacement remains.
- [x] Run all JVM tests, debug APK assembly and Android lint. Report hardware validation limits.

Keep the existing signing configuration, dependencies and database schema. Check deleting a non-default device, selecting a missing ID and updating an existing TV without changing its credentials.

Playback regression: Samsung PLAY, PAUSE and PLAY_PAUSE previously each sent two commands. Send only the mapped command, verified by recording actual WebSocket payloads.

Review also identified delayed updates to deleted IDs clearing the remaining default; retain no-op update behavior before changing default flags. Regression reproduced before the guard was added.

Full-suite follow-up: deleting a device while its app catalog initializes can raise a foreign-key exception. Reproduced with a deleted-parent test. The catalog now checks its parent and initializes within one Room transaction; ViewModel test cleanup waits for cancellation before closing Room.

Compatibility follow-up: lint identified five unguarded Tile.subtitle assignments requiring API 29 despite minSdk 24. Guarded each assignment. Kept the Intent launcher for API 24–33 and PendingIntent for API 34+, with a narrow documented suppression for the lint detector that does not recognize this compatibility branch.

Final validation: `gradle --offline --console=plain --no-daemon --no-configuration-cache :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` passed. 80 tests, zero failures/errors/skips, no uncaught asynchronous exceptions in test reports. Android lint: {'Warning': 42}. No Android device or emulator attached; physical TV discovery, pairing and commands remain unverified.

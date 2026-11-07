# Flexible Sleep Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline. Steps use checkbox syntax for tracking.

**Goal:** Personalizar Sleep y sumar 15 minutos sin reiniciar su cuenta pendiente.
**Architecture:** Ampliar el administrador de alarma existente; separar el diálogo de opciones y la coordinación de permisos de MainScreen para verificar el flujo real en Compose.
**Tech Stack:** Kotlin, Compose Material3, AlarmManager, SharedPreferences, Robolectric.
**Spec:** docs/superpowers/specs/2026-11-07-flexible-sleep-design.md

## Global Constraints

- Mantener 30 → 60 → 120 → OFF, el destino persistido y la verificación del apagado.
- Duración 1–1440 minutos; hora local de 24 horas, próxima ocurrencia.
- Extensión de 900000 ms desde el vencimiento, con token vigente y antes de vencer.
- Sin dependencias nuevas ni cambios de protocolos/autorización.

## Review Focus

- Hora que cruza medianoche o cambios de horario de verano: próxima fecha válida.
- Cambiar/eliminar la TV durante diálogo o permiso: nunca programar otro equipo.
- Pulsaciones de extensión atrasadas: no reemplazar una generación más reciente.
- Permiso rechazado: conservar entradas y alarma anterior.
- APK antigua con timer guardado: restauración compatible y ciclo correcto.

### Task 1: Scheduling and persistence

**Files:** SleepTimerManager.kt, new sleep/SleepTimerSchedule.kt, SleepTimerTest.kt.
**Interfaces:** scheduleFor(device, minutes), scheduleAt(device, hour, minute), extend(token) return SleepTimerState (nullable for stale extension); nextSleepOccurrence(hour, minute, nowMillis, timeZone) returns epoch milliseconds.

- [x] Add failing tests for custom duration, midnight/timezone, invalid inputs, extension after elapsed time, stale/expired tokens, permission rejection and persistence/legacy presets.
- [x] Run targeted tests; confirm the missing interfaces fail before implementation.
- [x] Implement via the existing monotonic alarm replacement; persist nullable quick preset separately from custom minutes.
- [x] Run SleepTimerTest; all 23 pass.

### Task 2: Options, long press and permission coordination

**Files:** SleepTimerButton.kt, DeviceSelectorBar.kt, SleepTimerCountdown.kt, new SleepTimerOptionsDialog.kt and SleepTimerControls.kt, MainScreen.kt, MainViewModel.kt; new FlexibleSleepUiTest.kt.
**Interfaces:** header onSleepLongClick; notice onExtend; dialog duration/time callbacks; controls retain the exact pending request through permission and recheck device/token.

- [x] Add failing Compose tests for long press without advancing, duration input/invalid input, time confirmation, +15 remaining-time behavior, cancellation and stale selection/permission paths.
- [x] Run tests; confirm failures describe absent behavior.
- [x] Wire new manager operations through ViewModel; extract Sleep permission UI into controls, using existing hardware volume forwarding in dialogs.
- [x] Run UI and existing Sleep tests; all pass, including 11 new UI cases.

### Task 3: Verification and documentation

**Files:** docs/SLEEP.md and implementation files if verification requires corrections.

- [x] Update usage and limits, including clock changes and next-occurrence semantics.
- [x] Run :app:testDebugUnitTest :app:lintDebug :app:assembleDebug. BUILD SUCCESSFUL on main 6445cf4 (PR #43) plus flexible Sleep: 279 tests, zero failures/errors/skips; lint zero errors, 89 warnings.
- [x] Request review of diff against spec; fix material findings and rerun affected/full checks as justified.
- [x] Prepare APK at /workspace/shared/downloads/OmniRemote-Sleep-flexible-PR43.apk; no physical hardware connected.

## Verification and review

Final JVM/Robolectric suite: **279 tests, 0 failures, 0 errors, 0 skipped**.
Independent review found ambiguous/nonexistent DST clock resolution. Added literal
New York spring/fall fixtures, observed the failure, and now resolve the earliest
exact future occurrence, skipping nonexistent local times. All fixtures pass.

A separate UI regression reproduced a late Settings result overwriting a newer
30-minute preset with an old 47-minute request. Explicit actions now supersede
pending permission requests; that regression and all other UI tests pass.

The initial APK used PR #42 as its base. A subsequent GitHub check found merged
PR #43 missing from the checkout. Fetched origin/main and fast-forwarded to 6445cf4
with the flexible Sleep changes reapplied cleanly. Independent integration review
found no material issues. PR #43 already includes the documented MainActivity lint
suppression; final lint is **0 errors, 89 warnings**.

Added an integration test: closing the mini remote in background preserves a custom
Sleep, and extension adds 900000 ms to its saved deadline and preserves its target.
The fixture initially reused a singleton from a previous Robolectric Application;
using a manager owned by this test's Application isolates its preferences. The
complete 279-test suite passes after this test-only fixture correction.

Final command: `gradle --offline --console=plain --no-daemon :app:testDebugUnitTest
:app:assembleDebug :app:lintDebug` → **BUILD SUCCESSFUL**. APK signature verified;
DEX inspected for both flexible Sleep and QuickRemote/RemoteSession classes. APK
SHA-256: d7c643d468b099320e27c17f9c60a77503ac45bdb105753a4ff18297d792c8d6.

No Android device/emulator is configured (`adb devices` returns an empty list), so
physical TV power-off is not claimed as tested.

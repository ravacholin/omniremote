# Samsung session recovery implementation plan

**Goal:** preserve Samsung authorization across terminal socket events and recover after standby without replacing a pending permission request.

**Architecture:** keep the existing WSS 8002 client and persisted identity. Retire a session before publishing terminal callbacks, bound transport/authorization waits, and make foreground recovery respect the live session. No failed POWER command is replayed.

**Tech stack:** Kotlin, OkHttp, coroutines, SharedPreferences, Robolectric.

**Evidence:** prior Samsung tests pass on the unmodified tree. `reconnectIfFailed` ignores a normal close; terminal WebSocket callbacks leave their generation active; the client has no authorization deadline. Reference samsungtv_smart also validates the channel connection identity before accepting a token. These are code-level defects; the repeated prompt on the user's firmware is not yet reproduced.

## Constraints and review focus

- Keep the name OmniRemote and existing tokens; never retry without a saved token as an automatic fallback.
- A dead socket must never authorize a session or overwrite a token.
- Explicit retries, repeated WOL and foreground events must preserve pending authorization.
- Both transport establishment and waiting for channel authorization must end eventually.
- Ignore connection broadcasts identifying another client; accept legacy messages without identity metadata.
- No hardware is connected to this environment; distinguish local verification from physical validation.

## Execution (in this session)

- [x] Write regression tests for terminal events, foreign connection identity, duplicate pending connect and normal-close foreground recovery; run and record failures.
- [x] Fix session retirement and pending-session preservation; rerun those tests.
- [x] Add virtual-time tests for stalled transport/authorization and implement deadlines that retire the old socket before reporting failure.
- [x] Exercise real TLS/WebSocket reconnects with token renewal and client recreation, plus the full JVM suite and debug APK build.
- [x] Review the final diff, document exact evidence and physical validation limits.

## Corrección de alcance tras la respuesta del usuario

Modelo UN50RU7100GCFV; usa exclusivamente POWER. Se retiró del cambio la
corrección de la carrera con Sleep. La causa del permiso repetido en su
equipo sigue sin demostrarse. Se agrega captura persistente de la secuencia
real de autorización y exportación por portapapeles, sin tokens. La revisión
independiente detectó dos carreras adicionales: un deadline cancelado que ya
esperaba el monitor y un reintento desde tecla que el controlador aún veía
como Disconnected. Ambas cuentan con regresiones que fallaron antes del arreglo.

## Resultado de validación

`gradle --console=plain --no-daemon :app:assembleDebug :app:testDebugUnitTest`: BUILD SUCCESSFUL. 188 pruebas, 33 suites, 0 fallos, 0 errores, 0 omitidas. La revisión independiente terminó sin hallazgos críticos o importantes pendientes. Se observaron advertencias de APIs obsoletas/SDK y una excepción no fatal del compilador KSP; no se interpretan como validación del equipo físico.

- [ ] Capturar y analizar el ciclo POWER → espera → abrir app → WOL en la UN50RU7100GCFV del usuario.
- [ ] Confirmar en esa TV la eliminación del permiso repetido. El problema original sigue abierto.

La validación final incluye la integración de `origin/main` en `0b8bdc2` (PR #35), conservando sus cambios de credenciales y verificación de destinos. El diagnóstico no atribuye a Sleep el caso informado con POWER.

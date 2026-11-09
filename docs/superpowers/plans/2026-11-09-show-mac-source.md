# Mostrame esto Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for inline execution.

**Goal:** Mostrar la app o pestaña exacta elegida sin alterar reproducción.
**Architecture:** Operación JXA `reveal` sobre identidad del #45, transportada por
MacMediaController con las guardas existentes; botón Compose y callback del ViewModel.
**Tech Stack:** Kotlin, Compose, coroutines, JXA, SSH, Node test harness, Robolectric.
**Spec:** docs/superpowers/specs/2026-11-09-show-mac-source-design.md

## Global Constraints

Sin dependencias nuevas, sin reproducción ni fallback global. Automático requiere
elegir fuente. No cambiar cola de enlaces ni notificaciones.

## Review Focus

Pestaña movida: usar ventana e índice actuales. JavaScript deshabilitado: mostrar
sin inspección DOM. Reinicio/cierre/cambio de servicio: rechazar sin activar otro
destino. Cambio de selección/conexión: invalidar comando pendiente. Busy/loading:
evitar solapamiento o estado atascado.

### Task 1: Host JXA

**Files:** mac_media_host.js, scripts/mac-media.test.cjs.
**Interface:** REQUEST.op=`reveal`, source existente; respuesta `revealed: true` o error.

- [x] Agregar pruebas: pestaña exacta movida/minimizada, sin execute/medios;
  procesos nativos, permisos y destinos obsoletos. Ejecutarlas y observar fallo.
- [x] Implementar validación de identidad y activación específica, restaurar ventana.
- [x] Ejecutar `node --test scripts/mac-media.test.cjs`; todas deben pasar (17/17).

### Task 2: Sesión y UI

**Files:** MacMedia.kt, MacMediaScripts.kt, MacMediaController.kt,
MacRemotePanel.kt, MacMediaSelector.kt, MainViewModel.kt, MainScreen.kt;
MacMediaControllerTest.kt y MacMediaSelectorTest.kt.
**Interface:** MacMediaScripts.reveal(source): MacScript;
MacMediaController.reveal(): Unit; MacMediaState.canReveal: Boolean;
MacMediaReply.revealed: Boolean; callback onRevealMedia: () -> Unit.

- [x] Agregar pruebas de comando dirigido, sin inspección de contenido, busy,
  errores, cancelación y bloqueo de Automático; UI: callback separado y disabled.
  Ejecutar y observar fallo.
- [x] Implementar operación con epoch/revisión/job, sin reintentar ni fallback;
  respuesta de error no debe inutilizar reproducción salvo identidad perdida.
- [x] Conectar botón «Mostrame esto» junto al selector y explicar Automático.
- [x] Ejecutar pruebas Mac; todas deben pasar (24/24 Android multimedia).

### Task 3: Cierre

- [x] Actualizar docs/MAC.md con comportamiento y checklist físico.
- [x] Ejecutar suite JVM, lintDebug, assembleDebug y harness Node.
- [x] Revisar diff completo y registrar resultados. Dejar APK local disponible.

## Resultados (2026-11-09)

- Nuevas pruebas JXA observadas en rojo antes de implementar reveal; 17/17 verdes.
- Pruebas Android nuevas observaron las API ausentes antes de la implementación;
  luego 24/24 pruebas multimedia y 329/329 de la suite completa, sin omisiones.
- `gradle --console=plain --no-daemon --no-configuration-cache -Pksp.incremental=false
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: BUILD SUCCESSFUL.
- Revisión independiente sin hallazgos materiales. `git diff --check` correcto.
- Firma APK verificada y ambos recursos JXA/web idénticos a los fuentes.
  APK: `app/build/outputs/apk/debug/app-debug.apk`.
- SHA-256: `12644b1d69c37783d82452bc9db44a6a575f9e0d8cb9178932d738e90b8d5ae5`.
- Entorno: se regeneró una transformación Gradle incompleta y se descargaron
  dependencias de tests faltantes; sin cambios de configuración en el repositorio.
- Validación física pendiente; checklist en `docs/MAC.md`.

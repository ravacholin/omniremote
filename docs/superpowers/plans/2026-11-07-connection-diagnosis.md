# Connection diagnosis implementation plan

**Goal:** Integrar un diagnóstico comprensible con recuperación conservadora.
**Architecture:** Runner con dependencias de red, evidencia tipada y resultados
inmutables; adaptador Android reutiliza escáner y registros; ViewModel gestiona
cancelación y acciones; diálogo Compose presenta comprobaciones y límites.
**Tech Stack:** Kotlin, coroutines, Room, Jetpack Compose, JUnit/Robolectric.
**Spec:** docs/superpowers/specs/2026-11-07-connection-diagnosis.md

## Constraints and review focus

- Nunca borrar credenciales ni interpretar un puerto como autorización.
- No cambiar de equipo por coincidencia de nombre o por una MAC ambigua.
- Ignorar resultados de trabajos cancelados y acciones sobre destinos obsoletos.
- Conservar actualizaciones concurrentes y no recrear equipos eliminados.
- Timeout/búsqueda incompleta y falta de red deben decir qué no se comprobó.

## Tasks

1. [x] Escribir y ejecutar pruebas de diagnóstico: falta de LAN, autorización,
   cambio de IP por MAC, IP reutilizada, identidad ausente/ambigua y cancelación.
   Implementar ConnectionDiagnosticRunner y adaptador Android.
2. [x] Probar lectura de eventos Samsung de la sesión más reciente y actualización
   condicional de IP en Room preservando credenciales/apps y evitando conflictos.
3. [x] Integrar estado y acciones en MainViewModel con cancelación por selección,
   eliminación/cierre y destino obsoleto. Añadir diálogo Compose y botón del mando.
   Probar estados visibles, acciones y cierre durante comprobaciones.
4. [x] Ejecutar testDebugUnitTest y assembleDebug, revisar diff y documentar límites.

## Progress

Diseño derivado del pedido; ejecución directa en el checkout aislado de la tarea.
Publicación solicitada después de implementar: commit y PR en
`feat/connection-diagnosis`, basada en `main` (`8a906b3`, incluye volumen mediante
botones físicos), y APK nuevo en Drive conservando la versión anterior.

Validación final: `gradle :app:testDebugUnitTest :app:assembleDebug --offline`
terminó con BUILD SUCCESSFUL. APK: `app/build/outputs/apk/debug/app-debug.apk`.
`git diff --check` sin errores. `adb devices` no muestra equipos: pendientes
las comprobaciones en la LAN y la autorización de una TV física.

Revisión independiente: corregidos tres hallazgos con pruebas que reprodujeron
el fallo antes del cambio: IP que responde sin identidad confirmada (buscar de
todos modos), Android TV con solo 6467 disponible, y acción WOL antigua durante
un permiso pendiente. Se bloquearon también las reparaciones de transporte cuando
la conexión pasó a Connecting/WaitingAuth y se filtraron eventos Samsung tardíos
de sesiones anteriores. No quedan hallazgos de revisión pendientes.

Integración con `main`: el diálogo de diagnóstico también debe reenviar los
botones físicos de volumen usando `ForwardRemoteVolumeKeys`; una prueba con la
ventana real del diálogo cubre esta continuidad con la función ya integrada.

Verificación después de integrar `main`: 236 pruebas, cero fallos/errores/omitidas;
`:app:assembleDebug` correcto. APK de 24.077.974 bytes, SHA-256
`bac52ba84e52255ee1ba215135e16e852432100cbd50af615c4b5404a5b1aff6`.
Subido como archivo nuevo a Drive y verificado por metadatos:
https://drive.google.com/file/d/1OBeohbAuCDM71mQv0gJByuEdwQ8E-3a7/view

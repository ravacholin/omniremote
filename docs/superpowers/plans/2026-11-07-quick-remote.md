# Quick Remote Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline, then one independent review.

**Goal:** Mini mando de acceso rápido que comparte sesión y respeta cerrar hasta reapertura.
**Architecture:** RemoteSession posee conexión y órdenes comunes; MainViewModel delega; QuickRemoteManager coordina ciclo de vida y QuickRemoteService publica RemoteViews.
**Tech Stack:** Kotlin, Android Service/Notification/RemoteViews, coroutines StateFlow, Robolectric.
**Spec:** docs/superpowers/specs/2026-11-07-quick-remote-design.md

## Global Constraints
- minSdk 24, targetSdk 36; sin dependencias nuevas ni cambios de firma.
- Servicio connectedDevice, no exportado, START_NOT_STICKY, notificación silenciosa.
- Una sesión y credenciales existentes. Cerrar no reaparece hasta reabrir realmente.
- Órdenes obsoletas no alcanzan otro dispositivo; no reenviar POWER.

## Review Focus
- Cierre mientras un inicio de servicio está pendiente: no resucitar.
- Rotación y retorno desde notificaciones: no confundir con reapertura tras cierre.
- Cambio o eliminación de TV durante orden encolada: rechazar destino obsoleto.
- Permiso denegado/canal bloqueado: no mantener servicio invisible.
- Proceso recreado por un PendingIntent antiguo: no restaurar el mando.

### Task 1: Sesión compartida y envío consistente
Files: crear controller/RemoteSession.kt; modificar ui/MainViewModel.kt y controller/UnifiedTvController.kt; pruebas QuickRemoteSessionTest.kt.
Interfaces: RemoteSession.getInstance(Context), controller, activeDevice, isPlaying, powerOnStatusMessage; selectDevice, sendKey, powerToggle, powerOnTv, disconnectForBackground, resumeConnection.
- [x] Escribir pruebas de sesión conservada entre ViewModels, play/pause compartido, orden invalidada al cambiar TV.
- [x] Ejecutar pruebas y confirmar fallo por funcionalidad ausente.
- [x] Extraer sesión y delegar desde ViewModel manteniendo funciones públicas; validar generación en órdenes pendientes.
- [x] Ejecutar pruebas nuevas y regresiones Samsung/selección/eliminación.

### Task 2: Ciclo de vida, servicio y notificación
Files: crear service/QuickRemoteManager.kt, service/QuickRemoteService.kt, service/QuickRemoteNotification.kt; layouts y drawables; modificar AndroidManifest.xml y MainActivity.kt; pruebas QuickRemoteServiceTest.kt.
Interfaces: manager.onAppForeground/onAppBackground(changingConfigurations), dismiss, refresh; acciones con token y destino inmutables; servicio observa la sesión compartida.
- [x] Escribir pruebas cierre/reconexión/reapertura/rotación, rechazo de token y destino viejos, permiso/canal bloqueado y proceso sin sesión.
- [x] Ejecutarlas y confirmar fallo por funcionalidad ausente.
- [x] Implementar manager, servicio, RemoteViews compacto/expandido, permiso tras conexión y comportamiento al descartar.
- [x] Comprobar notificación en API 24/33/36 y envío de controles a transporte simulado.

### Task 3: Validación y entrega
Files: docs/QUICK_REMOTE.md y los anteriores según hallazgos.
- [x] Ejecutar :app:testDebugUnitTest :app:assembleDebug :app:lintDebug; revisar cualquier fallo.
- [x] Revisar de forma independiente el cambio completo y corregir defectos materiales con regresiones.
- [x] Documentar uso, límites y prueba física pendiente; entregar APK y resultado de verificaciones.

## Execution notes
Se trabaja en el checkout limpio de /workspace/omniremote para que el resultado quede en el entorno compartido. La aprobación del alcance autoriza la ejecución continua sin nuevas confirmaciones. Baseline: :app:testDebugUnitTest BUILD SUCCESSFUL (caché).

## Evidencia de ejecución
- Sesión compartida: tres pruebas iniciales fallaron por sesión/reproducción no compartidas y POWER encolado al nuevo destino; después pasaron junto con SamsungWakeFlowTest, DeviceDeletionTest y SamsungReselectionTest.
- Notificación: validación de API 24, 33 y 36, seis controles accesibles y descarte mediante PendingIntent.
- Revisión independiente: cuatro hallazgos importantes (PIN al volver a foreground, bloqueo del canal en ejecución, cerrar desde una vista anterior y selección desde IO) y uno menor de diagnóstico Samsung. Todos corregidos. Se reprodujeron junto con cierre/reapertura y RemoteViews de Android 7: 19 pruebas, 7 fallos antes; 19 pruebas, 0 fallos después.
- Compatibilidad Android 7: se retiró setAlpha por no ser un método permitido de RemoteViews en esa versión. setEnabled conserva el bloqueo de botones mientras no hay conexión.
- Al cerrar se cancela el observador inmediatamente para que sus emisiones pendientes no descarten la siguiente apertura.
- La configuración de firma y la versión diagnóstica existente permanecen intactas.
- Pendiente exclusivamente de equipo físico: apariencia del panel del fabricante, Doze y encendido/autorización reales.
- Lint detectó Fragment 1.1 transitivo en registerForActivityResult. MainActivity hereda directamente de ComponentActivity, no de FragmentActivity: QuickRemotePermissionTest confirmó la entrega real de la solicitud y de su respuesta denegada en API 33. Se documentó una supresión local de ese falso positivo, sin añadir dependencias ni desactivar otras reglas.
- La suite completa reveló una limpieza prematura del fixture QuickRemoteSessionTest: se cerraba Room mientras la cancelación del ViewModel terminaba una transacción. Los fixtures ahora esperan la terminación de sus jobs antes de cerrar la base de datos. No se cambió producción para ocultar el fallo.
- Una regresión adicional reprodujo que retirar el último dispositivo activaba el bloqueo reservado al botón Cerrar. Se separó la retirada automática de la elección del usuario, conservando la aparición al conectar un dispositivo nuevo en la misma apertura.

## Resultado final
`gradle --console=plain --no-daemon --continue :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` → BUILD SUCCESSFUL. 257 pruebas, 0 fallos, 0 errores, 0 omitidas. Lint: 0 errores y 89 avisos de mantenimiento/estilo. `git diff --check` sin incidencias. Firma del APK verificada con apksigner (v2, compatible con minSdk 24).

APK generado: `app/build/outputs/apk/debug/app-debug.apk` (24 158 626 bytes). La rama se actualizó sobre main (12ba940), incorporando volumen físico y diagnóstico de conexión. El APK y el código se entregan mediante Drive y una PR contra main. La comprobación física descrita en `docs/QUICK_REMOTE.md` sigue pendiente por no haber teléfono/TV conectados al entorno.

- Integración para la PR: se conservó el diagnóstico al seleccionar mediante RemoteSession y el manejo de volumen físico de MainActivity. Las 257 pruebas de la combinación pasaron. Se documentó una supresión local RestrictedApi sobre el callback público dispatchKeyEvent heredado del shim de AndroidX Core; no se modificó su comportamiento.

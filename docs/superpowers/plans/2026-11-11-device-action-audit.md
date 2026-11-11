# Auditoría de operaciones al cambiar de dispositivo

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Corregir operaciones pendientes que conservan un destino antiguo o actúan sobre una selección nueva.

**Architecture:** Mantener las sesiones y la persistencia existentes. Cancelar la interfaz de vinculación al cambiar el endpoint, invalidar inmediatamente todos los dispositivos eliminados y conservar la generación de selección al guardar una MAC.

**Tech Stack:** Kotlin, coroutines, Room, Robolectric, Gradle.

**Spec:** Petición del usuario: buscar y corregir errores y mejorar las funcionalidades existentes de Omniremote.

## Global Constraints

- Conservar identidad Android, firma, credenciales y datos guardados.
- No enviar comandos a equipos físicos durante las pruebas.
- No publicar ni instalar automáticamente.

## Review Focus

- Reseleccionar el mismo endpoint debe conservar el PIN pendiente.
- Cambiar IP, puerto SSH o tipo debe invalidar operaciones anteriores; las TV conservan sus puertos fijos de protocolo.
- Borrar una TV debe dejar de aceptar órdenes antes de esperar a Room.
- Una escritura antigua de MAC no debe encender otra TV ni cerrar su diálogo.
- Volver al dispositivo original no debe reactivar una operación cancelada.

### Task 1: Vinculación Android y eliminación

**Files:** `MainViewModel.kt`, `UnifiedTvController.kt`, `DeviceActionLifecycleTest.kt`, `DeviceDeletionTest.kt`.

- [x] Reproducir diálogo PIN obsoleto tras cambio de destino y eliminación bloqueada por Room.
- [x] Cerrar/cancelar vinculación al cambiar de endpoint y al borrar el equipo activo; rechazar vinculación de equipos no Android.
- [x] Verificar los casos corregidos y la conservación al reseleccionar el mismo equipo.

### Task 2: Guardado de MAC

**Files:** `MainViewModel.kt`, `DeviceActionLifecycleTest.kt`.

- [x] Reproducir guardado de MAC de A que inicia encendido o cierra configuración de B.
- [x] Asociar la continuación a la generación y endpoint originales, conservando la actualización de la fila correcta.
- [x] Verificar cambio A→B, A→B→A y guardado normal.

### Task 3: Verificación y revisión

- [x] Ejecutar suite JVM/Robolectric, JavaScript, lint y compilación debug.
- [x] Revisar independientemente el diff y corregir hallazgos importantes con regresiones.
- [x] Documentar resultados y límites físicos; dejar APK disponible.

## Registro

- Inicio: checkout limpio en rama `work`, base `4870795`.
- La primera ejecución encontró una entrada incompleta de caché Gradle. Se movió a scratch para regenerarla sin modificar el proyecto.
- La autorización de reparación del usuario permite ejecutar las correcciones sin una confirmación intermedia.
- Primera ejecución de código original con regresiones: 404 pruebas, 8 fallos esperados, 0 errores y 0 omisiones. JavaScript: 17/17. Lint inicial: 78 avisos, 0 errores.
- Segunda ejecución específica tras las correcciones iniciales: 16 pruebas, 13 correctas y 3 fallos adicionales esperados del flujo de MAC. Las ocho regresiones iniciales quedaron resueltas.
- Revisión independiente: confirmó que guardar una MAC de un dispositivo no seleccionado debía cerrar su diálogo y ejecutar «Guardar y Encender TV» sobre ese equipo; además, faltaba distinguir dos aperturas del mismo diálogo. Se añadieron regresiones (incluida recepción UDP real en loopback) antes de corregir ambos casos.
- El guardado de MAC conserva la escritura de datos solicitada. La generación del diálogo invalida efectos de interfaz/encendido obsoletos; la generación de selección evita reactivar solicitudes tras A→B→A. Un equipo no seleccionado se enciende mediante la ruta existente con destino explícito.
- Decisión de alcance: la ruta general de WOL del listado ya podía terminar publicando un estado después de un cambio de selección. No se amplía aquí a una reestructuración de todos sus trabajos; queda documentado para seguimiento. El nuevo guardado le entrega una MAC válida y no activa su recuperación de MAC ausente.

- Verificación final: BUILD SUCCESSFUL; 407 pruebas JVM/Robolectric y 17 JavaScript correctas; 0 errores lint y 78 avisos. Firma APK y diff comprobados. Los hallazgos de revisión quedaron cubiertos por regresiones que fallaron antes de corregirse y pasan en la suite completa.

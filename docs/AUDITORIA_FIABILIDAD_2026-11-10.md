# Auditoría de fiabilidad · 10 de noviembre de 2026

Revisión del código de conexiones y cambio de dispositivo, catálogo de apps,
favoritos, autorización de Mac, temporizador Sleep y servicios del mini mando.
Las correcciones se limitan a funciones existentes y conservan la base de datos,
la identidad de la app y la configuración de firma.

## Fallos reproducidos y cambios

| Caso | Causa | Corrección |
| --- | --- | --- |
| Dos pulsaciones rápidas en Favoritos dejan la app marcada | Ambas escribían el inverso de la misma captura antigua de la interfaz | El cambio se realiza con una operación SQL atómica sobre el valor guardado |
| Un texto pendiente llega a la nueva sesión de TV | El transporte consultaba la sesión actual después de esperar el bloqueo de escritura | Se captura el socket original y se comprueba junto con la generación del destino antes del envío |
| Un enlace de app pendiente se envía tras sustituir la sesión | La escritura no comprobaba que siguiera siendo la sesión original | Se comprueba el socket dentro del bloqueo; un error antiguo solo puede cerrar su propio socket |
| Una tarjeta de apps de la TV anterior abre una app en la recién seleccionada | La apertura no validaba el `deviceId` de la tarjeta | Se rechazan tarjetas de otro dispositivo y se conserva la generación del destino en las órdenes asíncronas |
| Borrar una Mac durante el login vuelve a guardar sus credenciales | La autorización guardaba las claves antes de comprobar la cancelación; una petición en cola capturaba la generación demasiado tarde | Se conserva la generación desde la petición, se valida el destino antes de comenzar y la comprobación, el guardado y la publicación de sesión se serializan con la desconexión |
| El envío de texto a Samsung anuncia éxito sin enviar nada | La ruta del controlador devolvía `true` sin llamar al transporte | Enviar una orden `SendInputString` con el texto UTF-8 en Base64 y reflejar si el socket aceptó la orden |

## Pruebas de regresión

- `AppFavoritesReliabilityTest`: base de datos Room real, pulsaciones concurrentes y conservación de metadatos.
- `CommandTargetReliabilityTest`: órdenes bloqueadas durante un cambio de socket y tarjetas de otro dispositivo; se comprueba que no se escriban paquetes.
- `MacRemoteTest`: servidor SSH local que demora la autorización mientras se desconecta y elimina el login.
- `SamsungPlaybackTest`: formato de entrada de texto Unicode y resultado del envío por el socket autorizado.
- Pruebas existentes de texto Unicode, protocolo de apertura de apps, autorización y reconexión, controles de Mac, Sleep, notificaciones e interfaz.

Cada fallo de la tabla se reprodujo con una prueba que fallaba antes de su
corrección. Se añadieron además comprobaciones de autorización, rechazo de
envíos y cancelación antes de iniciar el login.

## Verificación inicial de la auditoría

```bash
source /workspace/.tooling/env.sh
gradle --offline --console=plain --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
node --test scripts/mac-media.test.cjs
git diff --check
```

- Gradle: `BUILD SUCCESSFUL`, 10 de noviembre de 2026, 21:25 UTC.
- JVM/Robolectric: **365 pruebas**, 60 clases, **0 fallos, 0 errores y 0 omitidas**.
- JavaScript de Mac: **17 pruebas**, **0 fallos y 0 omitidas**.
- Lint: **0 errores y 78 avisos**. Predominan recomendaciones KTX, recursos sin uso, versiones de dependencias, preferencias y gestores de certificados de los transportes locales.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, 23 984 794 bytes.
- Revisión independiente: las correcciones iniciales no presentaban regresiones detectadas; el caso adicional de login en cola señalado por la revisión quedó cubierto y pasó en la suite final.
- `git diff --check`: sin incidencias.

## Límites

Al preparar el PR se incorporó `main` en `9ea372a` (PR #51). Sus protecciones de
login y sesión de Mac se conservan, incluidas las garantías que coinciden con
esta auditoría. Este PR añade las pruebas de regresión de autorización en curso
y en cola sobre esa implementación, además de los cambios de comandos,
favoritos y texto Samsung. El APK de Drive se reemplaza por la compilación de
esta integración.

Verificación final de la integración: `BUILD SUCCESSFUL`; **385 pruebas
JVM/Robolectric en 67 clases**, sin fallos, errores ni omisiones, y **17 pruebas
JavaScript** sin fallos. Lint: **0 errores y 78 avisos**. APK de Drive:
**24 385 582 bytes**, SHA-256
`22e13782364938fd5e71fe4b81d173a1806cf0dbd40570ccd8c5330a865f4987`.

`adb devices` no detecta dispositivos conectados. Las pruebas automatizadas y
los servidores locales no validan el comportamiento físico de un Samsung,
FlowBox/Android TV o Mac en la red del usuario. Queda pendiente comprobar allí
descubrimiento, vinculación, encendido/apagado y controles reales.

# Auditoría de fiabilidad · 11 de noviembre de 2026

Revisión de conexiones Samsung/Android TV, cambios de destino, vinculación,
guardado de dispositivos, Wake-on-LAN, brillo/ADB, Sleep y controles de Mac.
Las correcciones se concentran en operaciones que terminaban después de que
el usuario cambiara de equipo o de diálogo. No cambian la identidad Android,
la firma, el esquema de datos ni las credenciales guardadas.

## Fallos y correcciones

| Caso | Resultado corregido |
| --- | --- |
| Cambiar de TV o de IP con un PIN abierto | Se cancela la vinculación anterior y se limpian diálogo, error y estado ocupado. Reseleccionar el mismo endpoint conserva el PIN. |
| Solicitar vinculación Android con una Mac seleccionada | Se rechaza antes de alterar su conexión; tanto el ViewModel como el controlador comprueban el tipo de equipo. |
| Borrar Samsung/Android TV mientras Room está ocupado | La selección y los transportes se invalidan inmediatamente, igual que ya ocurría para Mac. La eliminación persistente y selección de un equipo restante continúan después. |
| Guardar una MAC mientras cambia la selección, incluso A→B→A | Se guarda la fila solicitada, pero su finalización no inicia un encendido sobre una selección posterior. |
| Cerrar y reabrir la configuración de MAC mientras se guarda | La finalización antigua no cierra el nuevo diálogo ni recupera su solicitud de encendido. |
| «Guardar y Encender TV» en un equipo no seleccionado | Se cierra su diálogo y se envía Wake-on-LAN al equipo editado, conservando la selección del mando. |

## Evidencia de regresión

- `DeviceActionLifecycleTest`: cambios de TV/IP, conservación del PIN al
  renombrar, rechazo de tipo incorrecto, guardado normal y bloqueado en Room,
  reapertura de diálogo y paquete UDP recibido en loopback para el equipo editado.
- `DeviceDeletionTest`: eliminación suspendida por una transacción Room real
  para Mac, Samsung y Android TV; se comprueba la invalidación antes de desbloquearla.
- Código original más primeras regresiones: 404 pruebas, 8 fallos esperados,
  0 errores, 0 omitidas. Los demás casos pasaron.
- Tras las primeras correcciones: 16 pruebas específicas, 13 correctas y 3
  fallos esperados de los casos adicionales de MAC. Los ocho fallos iniciales
  quedaron resueltos. La revisión independiente confirmó esos casos adicionales.
- JavaScript multimedia de Mac: 17 pruebas correctas, ninguna omitida.

## Verificación final

Gradle: **BUILD SUCCESSFUL**. **407 pruebas JVM/Robolectric en 70 clases**, sin fallos, errores ni omisiones. JavaScript: **17/17**. Lint: **0 errores y 78 avisos**, el mismo número que antes de los cambios. APK debug generado y firma verificada con `apksigner verify`. `git diff --check` sin incidencias.

APK: `app/build/outputs/apk/debug/app-debug.apk`. SHA-256: `f7c43cc263c979823a7aa8bbce6c579ae1c7377fc31a7fdccceceef113918134`. Cambios locales, sin publicar.

Comandos de verificación:

```bash
source /workspace/.tooling/env.sh
gradle --offline --console=plain --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
node --test scripts/mac-media.test.cjs
git diff --check
```

## Entorno y límites

Se regeneró una entrada incompleta de caché Gradle y se descargaron las
dependencias de prueba que faltaban. KSP emitió una excepción AWT durante
la compilación, también observada en la revisión anterior; se comprueba
el resultado final de Gradle y los informes XML por separado.

`adb devices` no detecta equipos conectados. Las pruebas de protocolo y de
interfaz JVM/Robolectric no certifican el comportamiento de TVs o Mac físicas.
Queda comprobar en la red del usuario la vinculación, conexión tras reposo,
encendido/apagado, brillo y controles de cada modelo. No se publica ni instala
el APK automáticamente.

Seguimiento: el Wake-on-LAN iniciado directamente desde la lista de equipos
conserva su ciclo de vida anterior y puede publicar un estado tardío después
de cambiar la selección. Esta auditoría protege la continuación del guardado
de MAC, pero no reestructura ese flujo general.

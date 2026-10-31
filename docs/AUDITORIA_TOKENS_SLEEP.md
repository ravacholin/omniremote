# Auditoría de credenciales y Sleep — 31/10/2026

## Fallos reproducidos

| Fallo | Consecuencia | Corrección |
| --- | --- | --- |
| Samsung interpreta un token vacío como permiso para buscar otro por IP | Reutiliza la autorización de otra TV después de un cambio de IP/hardware | Distinguir credencial omitida de credencial descartada y conservar ID/MAC en conexiones y reintentos |
| Controller y Sleep recuperan el token de Room después de rechazarlo por MAC | Se salta la comprobación de propietario | Resolver también el respaldo de Room dentro de la validación de identidad |
| Renovaciones simultáneas de Samsung comparten número de secuencia | Un token antiguo guardado por MAC gana al nuevo guardado por IP/ID | Serializar escritura y lectura con un bloqueo compartido entre instancias |
| Callback y descubrimiento tardío de MAC vuelven a guardar un token antiguo | Una sesión de interfaz pisa el token renovado por Sleep | Una sola persistencia por evento y asociación atómica de MAC con el token más reciente |
| Actualización de metadatos guarda una copia vieja de `authToken` | Restaura una credencial anterior en Room | Mantener el token actual durante actualizaciones ordinarias; renovarlo por `updateAuthToken` |
| Descubrimiento usa solamente IP como identidad de la fila | Cambia el destino de un Sleep existente o mezcla credenciales de dos equipos | Usar MAC y protocolo para conservar la identidad del hardware; crear otra fila si el ocupante de la IP cambió |
| Android Sleep confía solamente en una IP y en la identidad compartida del cliente | Puede apagar otra TV ya vinculada después de un cambio DHCP | Guardar y comprobar la clave pública del servidor por dispositivo y rechazar una MAC comprobablemente distinta |
| Error al leer el certificado Android genera otra clave RSA | Invalida las autorizaciones que usan la identidad compartida | Reparar el certificado desde la clave existente; preservar datos y devolver error si la clave no se puede recuperar |
| Caché creada sin contexto oculta la identidad persistida | Se conecta con una identidad temporal distinta | Preferir la identidad guardada al disponer de contexto; guardar la temporal si aún no había una identidad |
| Proceso termina después de reclamar el vencimiento de Sleep | El temporizador desaparece sin resultado | Guardar una marca de ejecución y mostrar interrupción al reiniciar, sin repetir POWER |
| Room usa migración destructiva cuando falta una migración | Borra dispositivos, credenciales de Room y apps guardadas | Exigir una migración compatible y conservar el archivo original |

## Identidad del destino Android

Las TVs Android comparten la identidad del cliente: autenticarse no demuestra por sí solo que una IP siga perteneciendo a la TV prevista. El apagado debe verificar la identidad del servidor previamente registrado y rechazar una MAC distinta si el dispositivo publica su identidad en la API local. Las instalaciones anteriores necesitan registrar primero la identidad del servidor al conectarse; sin una identidad previa ni una MAC comprobable no es posible demostrar a qué hardware pertenecía históricamente una IP. Conviene reservar las direcciones DHCP de las TVs.

## Límites de la validación

Las pruebas de protocolo usan servidores TCP/TLS locales y Robolectric; no sustituyen una comprobación con teléfono y TVs reales, permisos del sistema, Doze o HDMI-CEC. No hay un esquema histórico de Room versión 1 en el repositorio: se conserva la información y se rechaza una apertura incompatible, sin inventar una migración. La actualización conocida de versión 2 a 3 sigue cubierta por sus pruebas.

El reinicio y la detención forzada del teléfono/app siguen sujetos a las limitaciones documentadas en `SLEEP.md`. Una interrupción de apagado nunca dispara automáticamente otra tecla POWER, porque podría volver a encender el equipo.

## Verificación final

Comando ejecutado: `gradle --console=plain --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.

- Resultado: BUILD SUCCESSFUL.
- 172 pruebas en 31 suites; 0 fallos, 0 errores, 0 omitidas.
- Lint: 0 errores y 57 advertencias, incluyendo recomendaciones de dependencias, preferencias y TLS local.
- APK debug generado en `app/build/outputs/apk/debug/app-debug.apk` (24.004.503 bytes).
- `git diff --check`: sin errores.

La herramienta KSP emitió una excepción de AWT en segundo plano durante la generación; las tareas de compilación, pruebas y lint finalizaron correctamente. No se atribuye este mensaje a un fallo de ejecución de la aplicación.

La publicación incorpora también la corrección de puerto de `main` (`b5ffd88`): Sleep conserva Remote v2 en 6466 aunque el descubrimiento guarde otro puerto. Se repitieron build, las 172 pruebas y lint después de integrar ambos cambios. APK SHA-256: `4a3f65eea6017e3995debe0ba82998cbd132babc9e74419372d1903cb30c67a8`. La firma coincide con la del APK anterior publicado en Drive.

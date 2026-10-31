# Samsung: recuperación de sesión y credenciales

**Estado:** captura diagnóstica preparada; causa del permiso repetido en la TV real pendiente.

## Problema informado

Después de apagar la Samsung, esperar y volver a abrir OmniRemote, el mando no
conecta. WOL enciende la TV, pero vuelve a pedir autorización aunque ya había un
token confirmado. Las correcciones anteriores no demostraron el comportamiento
en el equipo físico.

El usuario confirma modelo **UN50RU7100GCFV**, última versión instalada y apagado
únicamente con POWER. Nunca probó Sleep en Samsung. La corrección de una carrera con Sleep se retiró
del cambio porque no explica su caso.

## Evidencia local

La batería anterior de autorización y persistencia pasa sin modificar producción.
Las nuevas pruebas fallan antes de cada corrección y reproducen:

1. El cierre normal deja el controlador en `Disconnected`; al volver al primer
   plano solo se recuperaban los estados `Error`.
2. Reintentar explícitamente elimina un socket que todavía espera el permiso.
3. Un callback de conexión después de un evento terminal sigue teniendo una
   generación válida y puede restaurar un token anterior.
4. Se acepta un `ms.channel.connect` que identifica otro cliente. La referencia
   `ha-samsungtv-smart/api/samsungws.py` valida `data.id` contra `data.clients`
   y el nombre del cliente antes de guardar `data.token`.
5. Un transporte o una autorización sin respuesta no tiene límite de espera.

## Comportamiento requerido

- Reusar la identidad `OmniRemote`, WSS 8002 y las credenciales existentes.
- Conservar una conexión pendiente ante nuevas solicitudes para la misma TV.
- Invalidar la generación antes de notificar un cierre o error, de modo que ni
  callbacks tardíos ni deadlines antiguos modifiquen la siguiente sesión.
- Esperar como máximo 12 segundos por el transporte y 60 segundos por la
  autorización después de abrir el WebSocket. Conservar el token al fallar.
- Recuperar una TV seleccionada al volver al primer plano después de un cierre.
- Validar identidad si el evento incluye metadatos; mantener compatibilidad
  con mensajes antiguos que omiten ambos campos de identidad.
- No reenviar POWER ni otras teclas que fallaron durante el arranque.

## Límites de la validación

No hay un dispositivo conectado en `adb devices` ni acceso a la red de la TV.
Las pruebas de transporte local validan TLS, WebSocket, persistencia y estados;
no prueban el firmware, sus permisos ni el encendido físico por WOL. No se debe
afirmar que el caso del usuario está resuelto de forma infalible sin esa prueba.

Referencias de protocolo consultadas:

- https://github.com/xchwarze/samsung-tv-ws-api/blob/master/samsungtvws/connection.py
- https://github.com/ollo69/ha-samsungtv-smart/blob/master/custom_components/samsungtv_smart/api/samsungws.py

## Captura necesaria del equipo real

Registrar en el teléfono el inicio con huella de credencial, apertura WSS,
confirmación o rechazo explícito de Samsung, token recibido/guardado (solo
huellas), POWER, solicitud WOL y cierre/deadline. Conservar las últimas 80
entradas entre reinicios. Copiar desde el administrador de dispositivos → Settings → Copiar diagnóstico Samsung.
La captura es evidencia pendiente, no una reparación confirmada del permiso
repetido. No se cambian ajustes ni autorizaciones de la TV automáticamente.

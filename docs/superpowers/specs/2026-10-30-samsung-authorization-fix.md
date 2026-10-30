# Autorización Samsung después de encender

El usuario informa que, después de encender la Samsung desde el teléfono, debe
aceptar de nuevo el permiso. La escritura síncrona de tokens ya existe; las
correcciones anteriores no cubren todo el ciclo de vida de la sesión.

La investigación encuentra estos fallos reproducibles en el cliente:

- `WebSocket.send()` acepta en cola teclas antes de `ms.channel.connect`; eso no
  significa que la TV haya autorizado el mando.
- Si un envío falla durante la conexión, `sendRawKey` puede cerrar ese intento y
  abrir otro. El bloqueo anterior solo cubre el evento `unauthorized`, no la
  espera inicial durante el arranque y el permiso.
- Los mensajes de sockets sustituidos se guardan antes de comprobar su generación.
  Una respuesta tardía puede reemplazar un token más reciente.
- Tras fallar un socket que esperaba permiso, `awaitingAuth` no se restablece,
  bloqueando la recuperación automática.
- La reconexión desde una tecla prefiere el token en memoria, aunque otra sesión
  (por ejemplo Sleep) ya haya guardado uno renovado. Debe releer el almacén.
- Volver a seleccionar la misma Samsung o actualizar su MAC conserva la sesión
  solo si ya figura conectada; si está conectando o esperando permiso, la cierra.

Se corrige el ciclo de vida existente: esperar la autorización antes de enviar
teclas, conservar el intento pendiente, ignorar respuestas antiguas y liberar la
espera al terminar el transporte. Se conserva la identidad OmniRemote, WSS 8002,
los datos guardados y la selección por IP/id/MAC. No se reenvían teclas fallidas:
repetir POWER podría apagar una TV que acaba de despertar.

Las pruebas sustituyen únicamente el transporte WebSocket de la TV y ejercitan
el cliente y SharedPreferences reales, incluida la recreación del almacén y del
cliente. Las pruebas del controlador comprueban la conservación de la selección
pendiente. La compilación y la batería JVM validan regresiones locales; no hay
acceso a una Samsung física para confirmar el comportamiento de su firmware.

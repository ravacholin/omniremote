# Mini mando en notificaciones

Al abrir OmniRemote y conectar con un dispositivo aparece una notificación silenciosa con su nombre. En Android 13 o posterior la primera conexión solicita permiso de notificaciones; si se deniega, el mando dentro de la app permanece disponible. Para habilitarlo después: Ajustes de Android → Aplicaciones → OmniRemote → Notificaciones. El canal «Mini mando» también debe estar habilitado.

La vista compacta ofrece volumen −, play/pause, volumen + y cerrar. Al expandir aparecen los seis controles: volumen +, volumen −, mute, play/pause, encender/apagar y cerrar. Tocar el cuerpo abre OmniRemote. La presentación final depende del panel de notificaciones del teléfono.

El dispositivo seleccionado en la app es el que controla la notificación. Las pulsaciones antiguas quedan invalidadas al cambiar de destino. Ambos mandos comparten conexión, credenciales y estado estimado de reproducción. El icono play/pause no afirma conocer el estado real de todas las aplicaciones de la TV.

Cerrar (o descartar cuando Android permite deslizarla) retira el mini mando hasta salir de la app y volver a abrirla. Una rotación, reconexión o cambio de dispositivo dentro de la misma apertura no lo reactiva. Con la app visible, cerrar el mini mando conserva el control de la pantalla; con la app en segundo plano, cierra la conexión y cancela los intentos de encendido pendientes. El temporizador Sleep mantiene su funcionamiento independiente.

El servicio es de tipo connectedDevice, no se reinicia automáticamente y no arranca con el teléfono. Conserva la conexión sin wake locks permanentes ni exploraciones continuas. Android o el fabricante pueden terminarlo; vuelve a abrir la app para recuperarlo. Una desconexión no equivale a una confirmación de apagado. Para despertar se usa el encendido por red ya existente y nunca se guarda POWER para reenviarlo después de reconectar. En Mac, POWER conserva el comportamiento de dormir la pantalla.

## Prueba en teléfono y dispositivo real

1. Conectar y aceptar notificaciones; comprobar nombre y los seis controles al expandir.
2. Cambiar a otra app y probar volumen, mute y reproducción.
3. Cambiar de dispositivo desde OmniRemote y verificar que solo responde el nuevo destino.
4. Cerrar desde el panel. Confirmar que no regresa mientras se sigue en la app, se gira la pantalla o cambia la conexión. Salir y volver a abrir: debe reaparecer al conectar.
5. Apagar y volver a encender desde el mini mando. El soporte de Wake-on-LAN depende del equipo y de la red; «señal enviada» no confirma encendido físico.
6. Apagar la pantalla del teléfono durante varios minutos y probar de nuevo. Comprobar ahorro de batería del fabricante si Android detiene la sesión.
7. Denegar permiso o bloquear el canal: comprobar que no aparece el mando ni queda su servicio activo. El control dentro de la app sigue disponible.

Las pruebas automatizadas usan transporte Samsung simulado y Robolectric para el ciclo de vida y las notificaciones. No sustituyen estos pasos físicos ni resuelven por sí mismas el diagnóstico de autorización Samsung que ya estaba pendiente.

## Validación de esta entrega

Compilación debug y firma del APK verificadas. Suite completa: 257 pruebas correctas; incluye 21 pruebas nuevas de sesión, controles, permisos, cierre/reapertura, retirada de dispositivos y transporte obsoleto. Lint finalizó sin errores (89 avisos de mantenimiento y estilo del proyecto). Las pruebas de RemoteViews cubren Android 7, 13 y 16. Prueba física pendiente.

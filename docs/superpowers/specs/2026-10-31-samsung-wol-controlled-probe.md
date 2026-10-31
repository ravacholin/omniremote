# Samsung: prueba controlada del encendido desde OmniRemote

## Evidencia y límite

El usuario abre OmniRemote con la UN50RU7100GCFV apagada, pulsa el encendido
WOL dentro del mando y recibe de nuevo el permiso de Samsung. No se cambia ese
recorrido por un control externo ni por otro acceso de Android.

La captura muestra intentos con la misma credencial, fallos TCP, un WebSocket
abierto 2,849 segundos después de solicitar WOL y entrega de otra credencial
10,034 segundos después de abrirlo. El usuario confirma aceptación manual.
La credencial nueva vuelve a autorizarse en 104, 45 y 5 ms con la TV encendida.
No hay evidencia suficiente para afirmar que el tiempo de arranque sea la causa.

## Experimento

En una variante identificada como **1.7-wol30-test (9)**, el mismo botón WOL
suspende los intentos Samsung anteriores y programa una sola conexión para
30 segundos después de la solicitud. Repetir WOL no extiende la espera; teclas,
primer plano y reintentos no pueden adelantarla. Se conserva la credencial y se
relee el almacén justo antes de conectar. Cambiar de TV, desconectar o fallar el
envío de WOL cancela la conexión programada. Una sesión ya autorizada se conserva.

Se registran inicio, reutilización, cancelación y fin de espera. La interfaz
avisa de la prueba y mantiene el estado de encendido hasta el nuevo intento.
No se registran tokens sin protección ni se modifican permisos de la TV.

Esta variante cambia deliberadamente una variable temporal para medir su efecto
en el mismo recorrido de usuario. **No es un arreglo confirmado ni una demora
permanente justificada.** Las pruebas locales validan la coordinación, no el
comportamiento del firmware. La respuesta de la TV física sigue siendo necesaria.

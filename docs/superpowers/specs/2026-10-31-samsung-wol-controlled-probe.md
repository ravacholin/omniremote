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

## Resultado físico y variante de 15 segundos

El usuario confirma que el ensayo de 30 segundos funciona sin volver a autorizar.
La nueva captura contiene 30,015 s de espera, 162 ms para abrir el WebSocket y
75 ms desde su apertura hasta `authorization_confirmed`. La huella de la
credencial coincide con la previamente guardada y no se recibe otro token en ese
intento. Esto confirma ese resultado físico; no establece el mínimo de espera
ni el mecanismo interno del firmware. El ensayo también aborta intentos previos,
por lo que no se atribuye el resultado únicamente a un número de segundos.

A pedido del usuario se prepara **1.7-wol15-test (9)**: reduce la espera programada
a 15 segundos, conservando toda la coordinación. El valor único de BuildConfig
controla tanto el temporizador como el mensaje. El APK de 30 segundos se conserva
y ambas variantes mantienen versionCode 9 y la misma firma para poder alternar
sin borrar datos. El comportamiento físico con 15 segundos sigue pendiente.

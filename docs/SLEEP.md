# Sleep

El botón junto a Encender/Apagar recorre **30 → 60 → 120 → OFF**. Cada toque empieza
el nuevo plazo completo desde ese momento. Un aviso confirma el valor y OFF cancela.
Un solo timer controla la TV seleccionada cuando se programa; el aviso identifica
esa TV y ofrece Cancelar. Cambiar de TV no cambia un timer existente. Volver a tocar
Sleep programa el siguiente plazo para la TV seleccionada en ese momento.

El botón y el aviso muestran el **tiempo restante en minutos y segundos** (por
ejemplo, 29:59), actualizado cada segundo mientras la app está visible. Se calcula
desde el vencimiento de la alarma, por lo que al volver del segundo plano o abrir
otra vez la app refleja también el tiempo transcurrido con el teléfono bloqueado.
El paso del tiempo no cambia el plazo elegido para el siguiente toque: después de
programar 30 minutos, el siguiente toque programa 60 minutos completos.
Si llega a cero antes de que el servicio tome la alarma, muestra «Vencido» y
«Esperando apagado»; el resultado posterior indica si se confirmó el reposo.

Funciona con Samsung Tizen y Google/Android TV. No se ofrece para Mac. Usa las
credenciales ya guardadas. En Samsung envía `KEY_POWEROFF`. En Android TV consulta
el estado de encendido que informa la sesión autenticada: si ya está en reposo no
envía ninguna tecla; si está encendido envía una sola pulsación de `POWER` (26),
igual que el botón normal del mando. Algunos aparatos HDMI no responden a `SLEEP`
(223). Sin un estado de encendido conocido o sin soporte de estado de energía,
no envía `POWER`. No usa Wake-on-LAN ni reintenta esa pulsación.

Después de enviar `POWER`, espera hasta cinco segundos a que Android TV informe
reposo. Un rechazo, una desconexión o la falta de confirmación se informa como
apagado sin confirmar; escribir la orden en la conexión no basta para informar
éxito. Si el aparato ya estaba en reposo, no se envía una orden nueva a la pantalla.
Con un Chromecast, TV Box o Flow Box por HDMI, el estado confirmado es el del
aparato. El apagado de la pantalla depende de HDMI-CEC y de su configuración en
ambos equipos. La comprobación útil es que el botón normal de apagado de
**OmniRemote** también apague la pantalla; el control físico puede usar infrarrojo.

La alarma exacta de Android funciona con la pantalla bloqueada y aunque se destruya
la pantalla o el proceso de la app. En Android 12+ puede requerir habilitar
**Alarmas y recordatorios**. Si se rechaza, Sleep no se activa. Al vencer se inicia
un servicio breve, con notificación mientras conecta y envía la orden. El resultado
se conserva y se muestra en la app, incluyendo fallos de conexión o autorización.

El teléfono debe mantener conexión a la red local de la TV. Una orden enviada no
demuestra que el hardware se haya apagado. Forzar la detención de la app, reiniciar
el teléfono o revocar las alarmas exactas cancela la programación del sistema; hay
que volver a programarla. Eliminar la TV cancela su Sleep. El estado no se incluye
en las copias de seguridad.

## Validación

```bash
source /workspace/.tooling/env.sh
gradle --console=plain --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Las pruebas verifican ciclo y vencimientos completos, reemplazo/cancelación de
alarmas, persistencia del destino, permiso denegado, reinicio del teléfono y apagado
por una conexión TLS real a un servidor que simula Android TV. Cubren `POWER` solo
desde encendido, ausencia de teclas cuando ya está en reposo, confirmación de reposo,
rechazo y falta de respuesta. No prueban hardware físico ni HDMI-CEC.
También verifican que la cuenta visual avance sin modificar la alarma, se recupere
al volver a la app, se reinicie al reprogramar y nunca muestre tiempo negativo.

Con teléfono y TVs reales:

1. Tocar Sleep cuatro veces y comprobar 30, 60, 120 y OFF. Esperar unos minutos antes
   de avanzar y comprobar que el nuevo plazo se cuenta completo. Comprobar que
   botón y aviso descuenten segundos y que al bloquear/desbloquear o cerrar/abrir
   la app el tiempo restante sea el correcto.
2. Activar en una TV, seleccionar otra y comprobar que el aviso conserva el destino.
3. Verificar el apagado al vencer con el teléfono bloqueado y tras cerrar la app
   desde Recientes, tanto en Samsung como en Google/Android TV.
4. Apagar la TV antes de vencer y verificar que Sleep no la vuelve a encender.
5. Probar OFF, eliminación de TV y permiso rechazado: no deben dejar apagados pendientes.
6. Desconectar Wi-Fi o revocar la autorización de la TV antes del vencimiento y
   comprobar que la app informa el fallo sin apagar otro dispositivo.

# Corrección de Sleep en aparatos Android TV por HDMI

El usuario informa que Sleep terminó con «Orden de apagado enviada» pero la pantalla
siguió encendida. Usa un aparato por HDMI y confirma que el botón normal de apagado
de OmniRemote sí apaga la pantalla. El PR #27 enviaba `SLEEP` (223), mientras que el
botón normal envía `POWER` (26). Además, Sleep cerraba la conexión inmediatamente
después de escribir la orden y no esperaba una actualización de estado.

Se conserva la alarma, el destino guardado y la cancelación. En Android TV, Sleep
usa el estado `RemoteStart.started` de su propia sesión autenticada. Si el aparato
ya informa reposo, no envía teclas. Si informa encendido y negoció la función de
estado de energía, envía una única pulsación de `POWER`, con la comprobación de
cancelación en la escritura. Espera hasta cinco segundos a `started = false`.
Un rechazo, desconexión o falta de confirmación no es un apagado confirmado.
No reintenta un toggle ni usa Wake-on-LAN. Samsung conserva su orden explícita.

Esto sustituye la restricción original de usar siempre una tecla de apagado
explícita en Android TV: el caso reportado requiere el comando del botón normal,
condicionado al estado conocido de encendido. Una actualización de reposo que ya
procesó la sesión impide enviar `POWER`. El estado remoto no es una operación
atómica con el hardware: otros controles pueden cambiarlo mientras viaja la orden.

La confirmación describe al aparato Android TV, no comprueba físicamente la
pantalla HDMI. El apagado de la pantalla depende de HDMI-CEC. El comportamiento
físico deberá comprobarse en el equipo del usuario; no hay TV ni emulador conectado.

Regresiones: TLS con encendido y confirmación de reposo, aparato inicialmente en
reposo, orden rechazada, falta de confirmación y cancelación/reprogramación/eliminación
mientras se conecta. La prueba previa solo comprobaba que se enviara 223.

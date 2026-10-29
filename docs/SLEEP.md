# Sleep

El botón junto a Encender/Apagar recorre **30 → 60 → 120 → OFF**. Cada toque empieza
el nuevo plazo completo desde ese momento. Un aviso confirma el valor y OFF cancela.
Un solo timer controla la TV seleccionada cuando se programa; el aviso identifica
esa TV y ofrece Cancelar. Cambiar de TV no cambia un timer existente. Volver a tocar
Sleep programa el siguiente plazo para la TV seleccionada en ese momento.

Funciona con Samsung Tizen y Google/Android TV. No se ofrece para Mac. Usa las
credenciales ya guardadas y órdenes de apagado explícitas: `KEY_POWEROFF` en Samsung,
`KEYCODE_SLEEP` (223) en Android TV. Nunca usa Wake-on-LAN ni alterna el encendido.

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
alarmas, persistencia del destino, permiso denegado, reinicio del teléfono y orden
223 sobre una conexión TLS real a un servidor que simula Android TV. No prueban
hardware físico.

Con teléfono y TVs reales:

1. Tocar Sleep cuatro veces y comprobar 30, 60, 120 y OFF. Esperar unos minutos antes
   de avanzar y comprobar que el nuevo plazo se cuenta completo.
2. Activar en una TV, seleccionar otra y comprobar que el aviso conserva el destino.
3. Verificar el apagado al vencer con el teléfono bloqueado y tras cerrar la app
   desde Recientes, tanto en Samsung como en Google/Android TV.
4. Apagar la TV antes de vencer y verificar que Sleep no la vuelve a encender.
5. Probar OFF, eliminación de TV y permiso rechazado: no deben dejar apagados pendientes.
6. Desconectar Wi-Fi o revocar la autorización de la TV antes del vencimiento y
   comprobar que la app informa el fallo sin apagar otro dispositivo.

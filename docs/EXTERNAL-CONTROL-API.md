# API local de control externo de OmniRemote

OmniRemote 2.0 incorpora un Bound Service Android para que Insto controle los dispositivos guardados en el mismo perfil de Android. La API usa Binder local, funciona sin abrir una Activity de OmniRemote y toma el destino del `deviceId` de cada llamada. Los comandos conservan el televisor seleccionado, el dispositivo predeterminado y la sesión de la UI.

## Identidad de Insto

El paquete autorizado es `com.aistudio.pingassist.qvrtkm`, el `applicationId` del proyecto de Insto. OmniRemote debe compilarse con la propiedad Gradle `instoCertificateSha256` y la huella SHA-256 del certificado del **firmante actual** del APK instalado. La huella acepta 64 dígitos hexadecimales, con separadores `:` opcionales. [Proyecto de Insto](https://github.com/ravacholin/Insto/blob/main/app/build.gradle.kts)

El `gradle.properties` del repositorio fija la huella pública que autoriza el APK de distribución de Insto. Para verificarla al actualizar el certificado, usá `apksigner verify --print-certs Insto.apk` con el APK que reciben los usuarios. Un valor vacío deja la API cerrada y un valor con formato inválido falla al compilar.

Cada llamada Binder, incluso el descubrimiento de versión, verifica el UID real, que a ese UID le corresponde exclusivamente el paquete configurado, que el paquete carece de UID compartido y que tiene un único firmante actual que coincide con la huella. El servicio rechaza coincidencias únicamente históricas y vuelve a verificar la identidad antes del despacho. Una rotación del certificado de Insto requiere compilar OmniRemote con la nueva huella. Una llamada sin autorización recibe `SecurityException`.

Las respuestas contienen identificadores locales y datos descriptivos. Las credenciales de Samsung, Android TV, ADB y SSH se usan dentro de OmniRemote. La interfaz también omite IPs, direcciones MAC, rutas de red y enlaces privados de las aplicaciones.

## Instalación y enlace

En el manifiesto de **Insto** declará la visibilidad de OmniRemote:

```xml
<queries>
    <package android:name="com.aistudio.omnitvremote.qvrtm" />
</queries>
```

Copiá [IRemoteCommandService.aidl](../app/src/main/aidl/com/example/external/IRemoteCommandService.aidl) a `src/main/aidl/com/example/external/` del módulo cliente y habilitá `buildFeatures.aidl`. Usá estos valores para el intent.

| Campo | Valor |
| --- | --- |
| Paquete | `com.aistudio.omnitvremote.qvrtm` |
| Clase | `com.example.external.RemoteCommandService` |
| Acción | `com.aistudio.omnitvremote.qvrtm.BIND_EXTERNAL_CONTROL` |
| Flags | `Context.BIND_AUTO_CREATE` |

```kotlin
val intent = Intent("com.aistudio.omnitvremote.qvrtm.BIND_EXTERNAL_CONTROL")
    .setComponent(ComponentName(
        "com.aistudio.omnitvremote.qvrtm",
        "com.example.external.RemoteCommandService"
    ))

val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val api = IRemoteCommandService.Stub.asInterface(binder)
        // ioScope usa Dispatchers.IO.
        ioScope.launch {
            check(api.apiVersion >= 1)
            val devices = JSONObject(api.listDevices(1, 0))
            // Procesar la respuesta y completar el recorrido de páginas si corresponde.
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        // Descartar el proxy. El sistema puede reconectar el binding.
    }
}
val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
// Cuando termine la interacción, llamá context.unbindService(connection) si bound es true.
```

Consultá desde un hilo de trabajo. Manejá `SecurityException`, `RemoteException`, `onBindingDied` y una instalación del otro lado sin la API. Para continuar un pedido tras reconectar, conservá el `executionId`. La API es de consulta y despacho mediante Binder, sin puerto HTTP, permisos de accesibilidad de OmniRemote ni inicio de Activity.

## AIDL y versionado

```aidl
package com.example.external;
interface IRemoteCommandService {
    int getApiVersion() = 0;
    String listDevices(int apiVersion, long afterDeviceId) = 1;
    String getDevice(int apiVersion, long deviceId) = 2;
    String listApps(int apiVersion, long deviceId, long afterAppId) = 3;
    String execute(int apiVersion, String executionId, long deviceId, String command, String argumentsJson) = 4;
    String getExecution(int apiVersion, String executionId) = 5;
}
```

`getApiVersion()` devuelve la versión más alta disponible. Pasá `1` a los métodos de v1. Los IDs de transacción, nombres y significado de los campos v1 son parte del contrato. Ignorá campos adicionales que pueda traer una respuesta futura. Los `long` son enteros de 64 bits y se representan como números enteros en JSON. Los clientes con soporte limitado de números deben preservar su precisión.

| Método | Parámetros y contenido de `data` |
| --- | --- |
| `listDevices` | `afterDeviceId=0` para empezar. Devuelve `devices` y `nextAfterId`. Cada elemento trae `deviceId`, `name`, `room` y `type`. |
| `getDevice` | `deviceId>0`. Devuelve los datos del dispositivo, `commands`, `keys`, `stateFields` y `canScheduleExactAlarms`. |
| `listApps` | `deviceId>0`, `afterAppId=0` para empezar. Devuelve `apps` y `nextAfterId`. Cada elemento trae `appId`, `name`, `category` y `favorite`. |
| `execute` | `deviceId>0`, `executionId` de 12 a 64 caracteres ASCII, letras, dígitos, guion o guion bajo. `command` distingue mayúsculas. `argumentsJson` es un objeto JSON de hasta 8192 unidades UTF-16 con los campos exactos de la tabla de comandos. Enviá `{}` si no hay parámetros. |
| `getExecution` | Lee el estado del `executionId` en la identidad autorizada. Consultá con el mismo ID hasta recibir un resultado terminal. |

Cada página trae hasta 32 filas por ID ascendente. Si `nextAfterId` es un entero, usalo en la próxima llamada. `null` indica fin. Los dispositivos de demostración quedan fuera. Las listas reflejan los registros guardados al momento de cada consulta. Los nombres y habitaciones se acotan a 256 unidades UTF-16 y la categoría de app a 128. `deviceId` y `appId` identifican filas persistentes de esta instalación. El ID de dispositivo sobrevive a cambios de IP, nombre y habitación que conserven la fila. Un borrado, reincorporación o reinicio de datos puede producir otro ID. Usá el `appId` de la lista del dispositivo de destino. La lista de apps corresponde a accesos directos registrados en OmniRemote y su contenido puede diferir de las apps instaladas en la TV.

Ejemplo de descubrimiento:

```json
{"apiVersion":1,"status":"VERIFIED","code":"OK","data":{"devices":[{"deviceId":7,"name":"FlowBox","room":"Living","type":"ANDROID_TV"}],"nextAfterId":null}}
```

## Comandos por dispositivo

Los tipos válidos son `ANDROID_TV`, `SAMSUNG_TIZEN` y `MACOS`. Consultá `getDevice` para resolver la configuración de la unidad. La capacidad anunciada describe lo que OmniRemote puede intentar con ese tipo y configuración. La disponibilidad también depende del modelo, red, emparejamiento, permisos y app de destino.

| Comando | Argumentos | Aplicación |
| --- | --- | --- |
| `GET_STATE` | `{}` | Los tres tipos. Estado observado y temporizador local de la TV. |
| `POWER_ON` | `{}` | Wake-on-LAN y mecanismos existentes por marca. Requiere una dirección MAC guardada o detectable. |
| `POWER_OFF` | `{}` | Samsung envía apagado dedicado. Android TV usa el apagado protegido de Sleep y confirma reposo cuando Remote v2 lo informa. En Mac manda dormir la pantalla. |
| `KEY` | `{"key":"VOL_UP"}` | Una tecla compatible de la lista `keys`. |
| `TEXT` | `{"text":"hola"}` | Samsung y Android TV. Texto de 1 a 1024 unidades UTF-16 sin NUL. Depende del campo activo y el método de entrada. |
| `SET_VOLUME` | `{"level":40}` | Mac y Android TV con telemetría de volumen negociada. Entero de 0 a 100. En Mac, el ajuste existente también desactiva mute. |
| `SET_MUTE` | `{"muted":true}` | Mac y Android TV con telemetría de volumen negociada. Booleano. Ajuste explícito con lectura posterior. |
| `OPEN_APP` | `{"appId":25}` | TV, ID positivo de un acceso directo guardado para ese dispositivo. La apertura de algunas apps puede mostrar la ficha de Play Store. |
| `OPEN_BRIGHTNESS` | `{}` | Android TV con TVDimmer registrado. Solicita la ficha de TVDimmer en Play Store usando Remote v2. La confirmación se hace en la TV. Para abrir TVDimmer directamente usá ADB cuando esté configurado. El protocolo no expone un valor de brillo ajustable ni confirma foco. |
| `OPEN_BRIGHTNESS_ADB` | `{}` | Android TV con TVDimmer y puerto ADB previamente configurados y autorizados en OmniRemote. Abre TVDimmer usando el cliente existente. |
| `OPEN_BLUETOOTH_AUDIO` | `{}` | Samsung. Secuencia existente de menú para RU7100. Se detiene en la lista de altavoces y depende del menú del modelo. |
| `SLEEP_FOR` | `{"minutes":30}` | TV, entero de 1 a 1440. Programa el apagado con la alarma del sistema. |
| `SLEEP_AT` | `{"hour":23,"minute":30}` | TV, hora local de 0 a 23 y minuto de 0 a 59. Próxima ocurrencia en la zona horaria del teléfono al programar. |
| `SLEEP_EXTEND` | `{}` | TV. Extiende quince minutos el temporizador vigente del destino. |
| `SLEEP_CANCEL` | `{}` | TV. Cancelación idempotente del temporizador del destino. Una orden ya transmitida puede surtir efecto. |
| `MAC_OPEN_LINK` | `{"url":"https://example.org/"}` | Mac, URL HTTP(S) válida de hasta 4096 unidades UTF-16. Abre en la Mac. |
| `MAC_MEDIA_LIST` | `{}` | Mac, descubre fuentes nativas o pestañas compatibles. Devuelve `sources` con `sourceId`, `service` e `issues` a nivel de lista. |
| `MAC_MEDIA_STATE` | `{"sourceId":"app:com.apple.Music:321"}` | Mac, inspecciona la fuente actual. Puede devolver `playback` (`PLAYING` o `PAUSED`) y `actions`. |
| `MAC_MEDIA_KEY` | `{"sourceId":"app:com.apple.Music:321","key":"PAUSE"}` | Mac, tecla de medios dirigida a la fuente observada. Revalida proceso y pista mediante el controlador de medios existente. |
| `MAC_MEDIA_REVEAL` | `{"sourceId":"app:com.apple.Music:321"}` | Mac, trae la app o pestaña al frente en la Mac. |

**Teclas TV.** Volumen relativo y mute con `VOL_UP`, `VOL_DOWN`, `MUTE`. Canales con `CH_UP`, `CH_DOWN`, `NUM_0` a `NUM_9` y `NUM_PRE_CH`. Navegación con `UP`, `DOWN`, `LEFT`, `RIGHT`, `ENTER`, `BACK`, `HOME`, `MENU`, `INFO`, `SOURCE`, `SETTINGS` y `EXIT`. Medios con `PLAY`, `PAUSE`, `PLAY_PAUSE`, `STOP`, `REWIND` y `FAST_FORWARD`. Colores con `COLOR_RED`, `COLOR_GREEN`, `COLOR_YELLOW` y `COLOR_BLUE`. Para energía usá `POWER_ON` o `POWER_OFF`. La API usa el keycode/protocolo existente para cada TV y el destino puede ignorar teclas según modelo o app. Android TV puede rechazar como incompatible el texto, el enlace de brillo o el apagado si Remote v2 no negocia esa capacidad. Samsung expone volumen relativo y mute por tecla. Android TV y Mac permiten ajustes absolutos donde hay telemetría. El control de brillo abre TVDimmer.

**Teclas Mac.** `VOL_UP`, `VOL_DOWN`, `MUTE`, `PLAY`, `PAUSE`, `PLAY_PAUSE`, `STOP`, `REWIND`, `FAST_FORWARD`. Usá `MAC_MEDIA_KEY` para dirigir medios a una fuente concreta. Las teclas explícitas de reproducción, pausa y stop apuntan a Spotify o Música. Para Chrome usá el control por fuente. Toggle y saltos de pista pueden usar la tecla de medios del sistema Mac si hay Accesibilidad. Las acciones de fuente se anuncian como `PLAY`, `PAUSE`, `PLAY_PAUSE`, `PREVIOUS` y `NEXT`. Para las últimas dos enviá las teclas `REWIND` y `FAST_FORWARD`. `STOP` se traduce a `PAUSE`. Los IDs de fuente son efímeros y tienen hasta 128 unidades UTF-16. Volvé a descubrirlos después de cerrar pestañas o reiniciar apps.

**Volumen de Android TV.** El servicio convierte el porcentaje al paso nativo más cercano y devuelve el porcentaje observado, que puede diferir por redondeo. Cada pulsación de volumen espera un reporte nuevo antes de avanzar. Si se pierde la observación o cambia la escala, se detiene y devuelve un resultado incierto. El mute explícito comprueba el estado antes de enviar como máximo un toggle y espera un reporte para verificar el resultado. [Esquema de telemetría Remote v2](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/remotemessage.proto)

**Sleep.** OmniRemote mantiene un único temporizador compartido con la UI. Programar desde la API para otro dispositivo mientras el temporizador está activo o en ejecución devuelve `TIMER_OTHER_DEVICE`. El dato `sleepTimer` es `null` sin temporizador para ese destino o un objeto con `minutes` (duración nominal) y `remainingSeconds` al momento de la observación. `sleepPowerOffPending` indica que el servicio Sleep ya reclamó el apagado. Programar requiere el permiso Android de alarmas exactas. El resultado de programar verifica el temporizador local, y el apagado ocurre posteriormente. Sleep conserva el funcionamiento existente frente a reinicios y cambios de permisos.

El adaptador de Insto puede mapear `omniremote_power(on)` a `POWER_ON` o `POWER_OFF`, `omniremote_volume(percent)` a `SET_VOLUME(level)` y `omniremote_mute(muted)` a `SET_MUTE(muted)`. Usá el ID estable de `listDevices` y las capacidades de `getDevice` para ofrecer solo los controles correspondientes al destino. [Adaptador de Insto](https://github.com/ravacholin/Insto/blob/main/app/src/main/java/com/example/phone/OmniremoteAdapter.kt)

## Estados, resultados y errores

Cada respuesta de texto es un objeto JSON con `apiVersion`, `status`, `code`, opcionalmente `executionId` y, si hay datos, `data`. `PENDING` es el único estado no terminal de un pedido admitido.

| Estado | Significado |
| --- | --- |
| `PENDING` | Se registró el pedido y sigue en ejecución. |
| `VERIFIED` | Se leyó el dato local o remoto correspondiente. Puede representar una observación parcial, por ejemplo reachability sin volumen. |
| `SENT` | Se despachó la orden al transporte o al destino. El efecto físico o visual sigue sin confirmar. |
| `UNCERTAIN` | El cliente carece de evidencia suficiente para afirmar el resultado. Puede haber efectos parciales o completos. Revisá el destino antes de emitir una orden nueva. |
| `REJECTED` | Petición inválida, no admitida o rechazada antes de ejecutar la acción solicitada. |
| `UNSUPPORTED` | Operación desconocida o incompatible con tipo, tecla o configuración del destino. |

`GET_STATE` consulta en el momento. `controlReachable: true` indica que el transporte pudo establecer una sesión. Android TV informa `power: true|false` si Remote v2 reporta el estado de energía. Android TV informa `volume` en porcentaje, `volumeLevel` y `volumeMax` en pasos nativos y `muted` cuando recibe el reporte de la salida. Mac informa `volume` y `muted` cuando macOS los devuelve. Samsung puede confirmar una sesión pero este transporte carece de telemetría de energía. El silencio de red no autoriza inferir apagado, pausa, mute ni ningún otro estado. Los campos desconocidos se omiten. El estado de reproducción de fuente Mac solo aparece si se observó. Repetir `getExecution` devuelve la observación del pedido original. Usá un ID nuevo para refrescarla.

| Código | Significado |
| --- | --- |
| `OK`, `ACCEPTED`, `DISPATCHED` | Operación verificada, admitida o enviada respectivamente. |
| `STORE_OPEN_REQUESTED` | Se envió una apertura de ficha de Play Store y puede requerir confirmación en la TV. |
| `UNSUPPORTED_VERSION`, `UNKNOWN_COMMAND`, `NOT_SUPPORTED` | Versión, comando o capacidad incompatible. |
| `INVALID_EXECUTION_ID`, `INVALID_ARGUMENTS`, `INVALID_URL` | Formato, tipo, rango o URL inválidos. |
| `UNKNOWN_DEVICE`, `UNKNOWN_APP`, `UNKNOWN_SOURCE` | ID inexistente o que no pertenece al destino. |
| `TARGET_CHANGED`, `TARGET_IDENTITY_CHANGED`, `SOURCE_CHANGED` | Cambió el destino registrado, su identidad observable o la fuente de medios. |
| `DEVICE_UNREACHABLE`, `DEVICE_AUTHORIZATION_REQUIRED` | Falló la apertura del canal o falta autorizar el control. En `GET_STATE` puede acompañar datos parciales locales. |
| `DEVICE_BUSY`, `SERVICE_BUSY` | Ya hay un pedido activo para ese dispositivo o se alcanzó el límite de ocho dispositivos activos. El nuevo pedido no se admitió. |
| `QUERY_TIMEOUT`, `QUERY_UNAVAILABLE` | Falló o venció una consulta de catálogo. |
| `LEDGER_FULL`, `STORAGE_UNAVAILABLE` | Registro lleno o persistencia no disponible. Una consulta de resultado puede quedar incierta. |
| `EXECUTION_ID_CONFLICT`, `UNKNOWN_EXECUTION` | El ID pertenece a otro pedido o no consta en el registro vigente. |
| `CALLER_NO_LONGER_AUTHORIZED`, `DEADLINE_EXCEEDED` | Se detuvo el pedido antes de despacharlo. |
| `TIMEOUT`, `INTERRUPTED`, `EXECUTION_FAILED` | Venció el trabajo, se interrumpió el proceso o surgió un error inesperado. |
| `MAC_ADDRESS_REQUIRED`, `WAKE_NOT_CONFIRMED` | Falta la MAC o no se pudo establecer que se enviaron los paquetes de encendido. |
| `POWER_OFF_NOT_CONFIRMED`, `TRANSMISSION_NOT_CONFIRMED` | No se pudo confirmar el apagado o la transmisión. |
| `INVALID_APP_CONFIGURATION` | Identificador de paquete de TVDimmer inválido. |
| `EXACT_ALARM_PERMISSION_REQUIRED`, `NO_ACTIVE_TIMER`, `TIMER_OTHER_DEVICE` | Falta permiso de alarma, no se puede extender el temporizador pedido o el temporizador pertenece a otro destino. |
| `VOLUME_NOT_OBSERVED`, `STATE_MISMATCH` | No hubo lectura de volumen o el valor observado difiere del solicitado. |
| `NO_MEDIA`, `MAC_COMMAND_NOT_CONFIRMED`, `MEDIA_DISCOVERY_FAILED`, `MEDIA_NOT_OBSERVED`, `MEDIA_NOT_CONFIRMED` | Sin reproductor o sin evidencia suficiente de ejecución, descubrimiento o estado de fuente. |

## Concurrencia, deduplicación y ejemplo

El servicio admite una ejecución por dispositivo registrado y hasta ocho dispositivos distintos en paralelo. No encola comandos detrás de un dispositivo ocupado. Un rechazo de admisión por ocupación permite volver a enviar el mismo pedido con el mismo ID. Un pedido admitido consume su ID y tiene un plazo de 35 segundos, con límites menores en algunos transportes. Las consultas de catálogo usan un plazo de tres segundos. Conservar el binding mientras se espera ayuda a que Android mantenga el proceso. Si un transporte tarda en cancelar, se retiene la exclusión para ese dispositivo hasta terminar su limpieza.

La aceptación persiste antes de contactar el destino. El registro conserva hasta 2048 IDs durante 24 horas, aun si el proceso reinicia, y devuelve `INTERRUPTED` si encuentra una aceptación cuya ejecución se perdió. Repetir ID y pedido con argumentos equivalentes recupera la misma ejecución. Cambiar comando, valores o dispositivo con el mismo ID devuelve `EXECUTION_ID_CONFLICT`. La retención usa el reloj del teléfono. Insto debe generar IDs globalmente únicos y mantener su correlación local. Para `MUTE`, `PLAY_PAUSE` y otras teclas relativas o toggle, no generes un ID nuevo para compensar una respuesta perdida, incierta o fuera de la ventana de retención. Android TV Sleep usa como máximo un toggle protegido por un reporte de encendido. El servicio evita repetirlo mientras espera confirmación.

```kotlin
val executionId = UUID.randomUUID().toString()
val accepted = JSONObject(api.execute(1, executionId, 7, "KEY", """{"key":"MUTE"}"""))
var reply = accepted
while (reply.getString("status") == "PENDING") {
    delay(400)
    reply = JSONObject(api.getExecution(1, executionId))
}
```

Otros pedidos válidos son `execute(1, id, 7, "GET_STATE", "{}")`, `execute(1, id, 7, "OPEN_APP", "{\"appId\":25}")` y `execute(1, id, 7, "SLEEP_FOR", "{\"minutes\":30}")`. Cada pedido distinto usa su propio ID. Al perder Binder, consultá el resultado al reconectar y conservá el ID original si necesitás volver a presentar el pedido.

## Android 16, pantalla bloqueada y configuración

Insto se enlaza con un componente explícito, sin arrancar el servicio con `startService` o `startForegroundService`. Los comandos de red usan un wakelock parcial acotado y no intentan desbloquear el teléfono ni iniciar una interfaz local. Después del primer desbloqueo posterior al arranque, el servicio puede atender con la pantalla apagada o bloqueada mientras Android mantenga la ejecución y la red esté disponible. Antes del primer desbloqueo, Room y las credenciales protegidas por el usuario todavía están protegidas. Una detención forzada, restricciones de proceso, perfil suspendido o pérdida de Wi-Fi/LAN interrumpen o impiden la ejecución. Si se habilitó por compatibilidad la protección de red local de Android 16, concedé a OmniRemote el permiso Dispositivos cercanos desde los ajustes del sistema antes de ejecutar comandos. El servicio no muestra el diálogo de permisos.

La configuración inicial se hace en OmniRemote y en el dispositivo cuando el protocolo lo requiere. Emparejá Android TV, autorizá Samsung, guardá el acceso SSH de Mac y configurá ADB de TVDimmer en la interfaz existente. Las funciones de medios Mac pueden requerir los permisos de Automatización/Accesibilidad y la opción de Chrome para JavaScript por Apple Events. Sleep continúa usando su alarma exacta y su servicio de apagado a la hora prevista. Para verificar en Android 16, usá Insto con la firma real, elegí un dispositivo guardado diferente del seleccionado, probá con el teléfono bloqueado tras el primer desbloqueo y consultá el mismo ID tras reconectar. Una app de prueba firmada con otro certificado debe recibir `SecurityException` en todas las llamadas.

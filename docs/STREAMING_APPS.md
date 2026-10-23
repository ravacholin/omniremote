# Accesos de streaming — Argentina

Catálogo integrado el 22 de octubre de 2026. No instala servicios ni inicia sesiones: las apps deben estar instaladas, el usuario debe tener su cuenta y la TV debe estar vinculada con OmniRemote.

| Servicio | Android / Google TV (paquete de TV) | Samsung Tizen |
| --- | --- | --- |
| Flow Argentina | `ar.com.flow.androidtv` | Se obtiene el ID real de Flow de la lista de apps instaladas de la TV (`name:Flow` es un selector interno, no un ID de Samsung). |
| HBO Max | `com.wbd.stream` | `3202301029760` |
| Paramount+ internacional / Argentina | `com.cbs.ca` | `3202110025305`; se prefiere el ID regional que devuelva la TV. |
| Disney+ | `com.disney.disneyplus` | `3201901017640`; se prefiere el ID instalado cuando esté disponible. |
| Apple TV | `com.apple.atve.androidtv.appletv` | `3201807016597` |

El catálogo se comparte entre los accesos predeterminados y el diálogo para añadir apps. Al abrir una TV guardada se agregan los servicios que faltan, conservando los nombres personalizados, favoritos, orden e historial de los registros existentes. Se corrigen los antiguos presets de Max y Paramount+; los paquetes estadounidenses de Paramount+ no se utilizan como opción predeterminada argentina.

Android envía `RemoteAppLinkLaunchRequest` (campo protobuf 90, enlace en campo 1) por la conexión Remote Service v2 autenticada, anunciando APP_LINK (512). Apple TV usa `https://tv.apple.com`, Paramount+ usa `https://www.paramountplus.com/`, HBO Max usa `https://play.hbomax.com` (alternativa `https://play.max.com`) y Disney+ usa `https://www.disneyplus.com`. Se reparan también los enlaces `market://launch` guardados por APK anteriores.

Flow abre directamente la ficha oficial de Google Play: el enlace anterior `market://launch` no es un deep link verificado de Flow. Se abre la ficha y el mando indica «Flow: selecciona Abrir en la pantalla de la TV». La misma alternativa se ofrece para los otros servicios si sus enlaces son rechazados. No se pulsa Enter automáticamente porque la ficha podría mostrar Instalar en lugar de Abrir. No se encontró un enlace directo universal verificable de Flow para todos los Android TV.

`RemoteError` después de iniciar la sesión describe una orden rechazada: no se cierra el socket ni se pide vincular otra vez. El rechazo se asocia al enlace solicitado; las peticiones de apertura se serializan. Un rechazo durante la negociación sigue siendo fatal, y EOF o un fallo de transporte siguen produciendo una desconexión. El protocolo no ofrece confirmación positiva de apertura: se espera una ventana de 900 ms para recibir un rechazo inmediato y no se presenta el silencio como comprobación de que la app esté en primer plano. Los errores tardíos se muestran como errores de la orden, conservando el mando conectado.

Samsung consulta `ed.installedApp.get` por la sesión vinculada y lanza mediante `ed.apps.launch`, respetando el tipo nativo/deep link. Algunos firmwares no exponen la lista: en ese caso Flow devuelve un error explícito; no se inventa un ID ni se sustituye por Samsung Flow. Los otros servicios conservan sus IDs conocidos. Una petición REST rechazada ya no se informa como exitosa.

## Corrección tras el reporte de Android TV

El APK anterior confundía RemoteError con pérdida de conexión. La prueba TLS reproduce la orden rechazada, comprueba que se siguen respondiendo pings y enviando teclas y verifica por separado que un cierre real del socket se detecta. También comprueba los enlaces enviados al recibir datos antiguos y la alternativa de Flow con confirmación manual.

## Fuentes

- [Flow Android TV — ficha del proveedor](https://play.google.com/store/apps/details?id=ar.com.flow.androidtv)
- [Flow — dispositivos compatibles](https://www.personal.com.ar/ayuda/tv-streaming/servicios/dispositivos-acceder-flow)
- [HBO Max — ficha Android](https://play.google.com/store/apps/details?id=com.wbd.stream)
- [HBO Max — ficha Samsung](https://www.samsung.com/us/tvs/smart-tv/samsung-tv-apps-detail/?appId=3202301029760)
- [Paramount+ internacional — ficha del proveedor](https://play.google.com/store/apps/details?id=com.cbs.ca)
- [Paramount+ — ficha Samsung](https://www.samsung.com/us/tvs/smart-tv/samsung-tv-apps-detail/?appId=3201710014981)
- [Disney+ — ficha Android](https://play.google.com/store/apps/details?id=com.disney.disneyplus)
- [Disney+ — ficha Samsung](https://www.samsung.com/us/tvs/smart-tv/samsung-tv-apps-detail/?appId=3201901017640)
- [Apple TV — ficha Android](https://play.google.com/store/apps/details?id=com.apple.atve.androidtv.appletv)
- [Apple TV — ficha Samsung](https://www.samsung.com/us/tvs/smart-tv/samsung-tv-apps-detail/?appId=3201807016597)
- [Catálogo de IDs Samsung y sus variantes regionales, mantenido por samsung-tv-ws-api](https://github.com/xchwarze/samsung-tv-ws-api/blob/master/APPLICATIONS.md)
- [Implementación Samsung de consulta y apertura](https://github.com/xchwarze/samsung-tv-ws-api/blob/master/samsungtvws/remote.py)
- [Remote Service v2: protocolo protobuf](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/remotemessage.proto)
- [Google: market://launch corresponde a Google Play Instant](https://developer.android.com/distribute/marketing-tools/linking-to-google-play)
- [Referencia de enlaces de apertura Android TV](https://rs.ltd/apps/remote-control-tv/custom-apps/android/)
- [Tratamiento de RemoteError sin cerrar la sesión en androidtvremote2](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/remote.py)
- [Home Assistant: apertura de apps y limitaciones actuales](https://www.home-assistant.io/integrations/androidtv_remote/)

Validación local: compilación y pruebas JVM/Robolectric, incluida transmisión de un lanzamiento por TLS contra un servidor de prueba. No hay TV física conectada para comprobar la apertura real ni las variantes del firmware Samsung.

## Reparación de Brightness, Flow y TVDimmer (v1.3)

Brightness comparte ahora el lanzamiento serializado y la indicación «selecciona Abrir» de Apps. Flow y TVDimmer usan sus fichas oficiales de Google Play sin enviar primero esquemas no verificados. También se corrigen los enlaces antiguos guardados para estas apps. Las pruebas TLS comprueban las URLs de ambas fichas, la indicación de confirmación manual y que RemoteError conserva el mando conectado; EOF real sigue notificando desconexión. No se ha reproducido el cierre de conexión de la captura en un FlowBox físico.

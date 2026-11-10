# Revisión de fiabilidad de OmniRemote

## Cambios

- Volumen de Mac: cada nivel pendiente conserva el dispositivo y la generación de conexión de origen. Se mantiene la agrupación de movimientos del slider y se rechazan órdenes/resultados de conexiones anteriores.
- Puerto SSH: volver a guardar una Mac con otro puerto invalida las órdenes pendientes y abre una conexión al nuevo endpoint, conservando el ID y la cola de contenidos.
- Login de Mac: los toques repetidos no encolan nuevas autorizaciones; cerrar el diálogo o cambiar de destino cancela la operación. Las sesiones provisionales se cierran ante cancelación, fallo SSH o error al guardar las credenciales. El guardado final se coordina con la desconexión para no restaurar credenciales de un dispositivo eliminado.
- Configuración ADB: un fallo de conexión inicial ya no invalida la búsqueda del paquete TVDimmer necesaria para abrir el diálogo sin conexión. Se conserva la cancelación con Back y al cambiar de destino.
- Búsqueda: errores y cancelaciones liberan el indicador de actividad y permiten reintentar. Cerrar resultados, cerrar el diálogo de dispositivos o destruir el ViewModel detiene la búsqueda. La cancelación se comprueba entre sondeos TCP y antes de publicar resultados; los sondeos que ya están bloqueados terminan dentro de sus límites de conexión/lectura existentes.
- Descubrimiento: las respuestas antiguas de NSD y del sondeo de red no pueden publicar resultados ni liberar el resolutor de una búsqueda nueva después de detener/reiniciar. Los sondeos de clasificación se realizan fuera del bloqueo del ciclo de vida. Los anuncios complementarios conservan MAC, modelo y nombre informativos, puerto Remote v2 y puerto SSH personalizado. Se rechazan prefijos IPv4 inválidos antes de abrir la búsqueda.

## Cola de contenidos para Mac

No se modifica `MacLinkQueue`, su DAO, su esquema ni su interfaz. El descarte de órdenes obsoletas se aplica al volumen, no a los contenidos guardados.

Se verifica el orden por Mac, conservación tras fallo de apertura, reapertura de la base de datos, aislamiento entre destinos, rechazo de aperturas simultáneas y convivencia con el reproductor seleccionado y el volumen. Una apertura aceptada elimina únicamente el enlace abierto, como antes.

## Revisión del resto de funciones

Se revisaron rutas de sesión, reconexión, controles, Sleep, mini mando, brillo/ADB y secuencias Bluetooth. Se conservan sus protocolos y comportamientos existentes. Las suites de regresión cubren Samsung, Android TV, credenciales, base de datos, temporizador, notificaciones, controles físicos y Mac; las pruebas JavaScript cubren selección y control de reproductores web.

No se actualizan dependencias, ni se cambia la firma, versión, diseño o esquema de la base de datos. Los avisos de lint sobre `commit()` no justifican eliminar las garantías de persistencia de credenciales y temporizadores.

## Comprobaciones físicas pendientes

Este entorno no tiene ningún dispositivo conectado en `adb devices`. No se ha comprobado el funcionamiento físico con Samsung, FlowBox o Mac reales.

1. Samsung y Android TV: conectar/vincular, reconectar después de reposo o cambio de Wi-Fi, probar teclas, texto y apertura de apps; cambiar de dispositivo mientras hay órdenes pendientes.
2. Mac: deslizar volumen y cambiar de destino; cerrar/reabrir login durante una autorización y probar credenciales erróneas. Guardar varios enlaces por Mac, cerrar/reabrir OmniRemote y abrirlos en orden.
3. Mini mando: controles con la app en segundo plano, cambio de destino, permiso de notificaciones denegado y cierre/reapertura.
4. Sleep: expiración con teléfono bloqueado, cancelación/extensión, cambio y eliminación de TV, y comprobación del apagado físico.
5. FlowBox: abrir TVDimmer y cancelar con Back durante la espera. Samsung RU7100: comprobar la navegación hasta la lista Bluetooth; el protocolo no observa el foco real del menú.

Ver los procedimientos específicos de `PRUEBAS_TV.md`, `MAC.md`, `QUICK_REMOTE.md`, `SLEEP.md` y `BRIGHTNESS.md`.

## Validación final

- Gradle `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: **BUILD SUCCESSFUL**.
- **375 pruebas JVM/Robolectric**, sin fallos, errores ni pruebas omitidas (20 pruebas nuevas respecto a la base de 355).
- **17 pruebas JavaScript**, sin fallos ni pruebas omitidas.
- Lint: **0 errores, 78 avisos existentes**.
- Firma del APK debug verificada.
- Revisión independiente completada; sus dos hallazgos de puerto SSH y callbacks antiguos se reprodujeron y corrigieron. Sin hallazgos menores aplazados.

Se mantiene el checkout existente. No se cambia el esquema ni se actualizan dependencias; la publicación se realiza como entrega separada, sin fusionar la PR. Los puertos de control de TV siguen siendo los fijados por sus protocolos; el puerto configurable de SSH forma parte de la identidad de la conexión Mac.

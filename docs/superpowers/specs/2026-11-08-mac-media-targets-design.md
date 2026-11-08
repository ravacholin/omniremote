# Selección de reproductor en Mac: informe de viabilidad y propuesta

Fecha: 2026-11-08. Proyecto: OmniRemote.
Estado: propuesta pendiente del OK del usuario. Este informe no autoriza implementación.

## Conclusión

Sí: OmniRemote puede ofrecer un selector de fuentes multimedia que reúna apps de Mac y pestañas concretas de Chrome. Se recomienda aprovechar la conexión SSH existente y añadir adaptadores por reproductor. No hace falta instalar un agente ni una extensión para iniciar este enfoque, aunque Chrome requiere una configuración adicional.

La viabilidad del mecanismo de Chrome está respaldada por su código público. La compatibilidad efectiva de cada servicio debe comprobarse en una Mac real: detectar una pestaña de Spotify o YouTube no demuestra que tenga un reproductor cargado ni que acepte todas las órdenes.

## Pedido y criterios

El usuario quiere elegir qué controlar cuando el dispositivo es una Mac: Spotify, YouTube y otros reproductores habituales, tanto apps como pestañas abiertas en Chrome. No quiere un listado indiscriminado de pestañas. Primero pide estudio e informe; la implementación queda para después de su OK.

Las decisiones de alcance y comportamiento que siguen son recomendaciones del informe, no acuerdos ya aprobados.

## Situación actual comprobada

- `MacSshClient` conecta por SSH, conserva una clave de acceso y ejecuta AppleScript/JXA mediante `osascript`. No instala software residente en la Mac.
- `MacScripts.media()` recorre Spotify y Música; actúa sobre el primero que declara estar reproduciendo. Después intenta una tecla multimedia global y, sin Accesibilidad, el primer reproductor abierto. No enumera Chrome ni permite fijar un destino.
- Una tecla global deja a macOS decidir el receptor. No identifica una pestaña concreta. Además, PLAY y PAUSE usan la misma tecla global de alternancia: no garantizan por separado «reproducir» o «pausar».
- `MacRemotePanel` tiene volumen general, silencio, anterior, play/pausa y siguiente. Actualmente no muestra fuente ni estado confirmado del reproductor.
- La pantalla y la notificación comparten `RemoteSession`. La selección multimedia deberá pertenecer a esa sesión para que ambos mandos actúen sobre la misma fuente.
- Las pruebas actuales de Mac verifican generación de scripts y transporte SSH simulado. No ejecutan AppleScript en macOS ni prueban un reproductor real.

## Enfoques considerados

| Enfoque | Ventaja | Coste o límite |
| --- | --- | --- |
| SSH + AppleScript/JXA + adaptadores web | Reutiliza el transporte y la configuración actual; apps y pestañas desde un mismo mando | Permisos de Chrome; mantenimiento de adaptadores; límites de acceso al reproductor web |
| Extensión de Chrome + puente local autenticado | Más opciones para observar navegación, medios y cambios de estado | Instalación y distribución adicionales; Chrome no controla por sí mismo las apps nativas; también requiere adaptadores |
| Agente residente en Mac, complementado para el navegador | Integración continua y más posibilidades de seguimiento | Otro producto que instalar, actualizar y mantener; un agente tampoco obtiene automáticamente control universal de pestañas |

**Recomendación: primer enfoque.** Encaja en OmniRemote y evita ampliar ahora el despliegue. Una extensión queda como alternativa futura si la validación descubre límites que impidan el uso deseado. No se incorporaría automáticamente sin revisar el alcance con el usuario.

## Experiencia propuesta

Sobre los controles de reproducción aparecería un selector «Controlar». Su valor inicial sería «Automático», conservando el criterio actual. Al abrirlo mostraría fuentes compatibles abiertas, diferenciando claramente app y Chrome.

Ejemplo ilustrativo del contenido, no inventario real de la Mac:

| Fuente | Identificación | Estado posible |
| --- | --- | --- |
| Automático | Selección del sistema actual | El destino puede variar |
| Spotify · app | Tema y artista, si están disponibles | Reproduciendo / pausado |
| Música · app | Tema y artista, si están disponibles | Reproduciendo / pausado |
| YouTube · Chrome | Título del vídeo · ventana A | Reproduciendo / pausado |
| YouTube · Chrome | Otro vídeo · ventana B | Reproduciendo / pausado |
| Spotify · Chrome | Título disponible | Control verificado / requiere configuración |

Elegir una fuente solo cambia el destino de las siguientes órdenes. No reproduce, pausa otras fuentes ni trae ventanas al frente. El usuario podrá cambiar expresamente a Automático.

La lista priorizará fuentes reproduciendo, luego pausadas y después candidatas pendientes de verificación. Una selección manual se mantiene aunque otra app empiece a sonar. Dos pestañas de YouTube se muestran por separado, incluso con títulos iguales; un identificador breve adicional permitirá distinguirlas.

Si se cierra la pestaña o la app elegida, la selección pasa a «Fuente no disponible» y se bloquean sus órdenes multimedia. No se envía el comando a otro reproductor. Si una pestaña cambia de servicio, se pide actualizar y volver a seleccionar. Dentro del mismo servicio, siguiente/autoplay o navegar a otro contenido conserva la selección de pestaña, previa actualización de estado y capacidades. Si ya no hay reproductor, queda temporalmente no disponible. Los cambios de título no cambian la identidad de la fuente.

Volumen y silencio seguirán siendo del sistema y estarán rotulados «Volumen de la Mac». Apagar conserva el significado actual de dormir la pantalla. La selección no convierte esos botones en volumen o apagado de Spotify/YouTube.

## Qué pestañas y apps aparecen

El descubrimiento tendrá dos fases:

1. **Candidatas:** detectar apps compatibles en ejecución y filtrar en la Mac las pestañas por host exacto y rutas reconocidas. No enviar al teléfono el inventario de sitios ajenos al catálogo. Se excluyen ventanas de incógnito.
2. **Verificación:** consultar únicamente las candidatas y comprobar reproductor, estado y acciones disponibles. Estar en un dominio conocido o emitir audio no basta para declarar control compatible.

Una página de inicio o búsqueda de YouTube sin reproductor no aparece como fuente utilizable. Una reproducción pausada sí aparece. Cuando faltan permisos, el selector muestra el problema de configuración de Chrome y, si puede reconocerlas, las candidatas con estado «Sin verificar»; no ofrece botones operativos. Con permisos y sin reproductor cargado, la candidata se omite de la lista utilizable.

No se promete descubrir cualquier app instalada ni cualquier web con un vídeo. El catálogo será ampliable mediante adaptadores probados.

### Alcance inicial recomendado

| Fuente | Detección y control propuestos | Condición |
| --- | --- | --- |
| Spotify nativo | Proceso/bundle ID + diccionario AppleScript | Automatización autorizada y app abierta |
| Música nativa | Proceso/bundle ID + diccionario AppleScript | Automatización autorizada y app abierta |
| YouTube en Chrome | URL admitida + vídeo HTML + controles del sitio | Automatización de Chrome y JavaScript por Apple Events |
| YouTube Music en Chrome | Adaptador propio, medio y controles del sitio | Mismos permisos; validar comportamiento real |
| Spotify web en Chrome | Adaptador propio para reproductor y controles DOM | Validación específica; no asumir equivalencia con Spotify nativo |

Estos cinco destinos son el objetivo de la primera entrega propuesta. Spotify web y YouTube Music requieren comprobarse antes de declararlos compatibles. Si alguno falla, se documenta el límite y se revisa la cobertura antes de dar la entrega por terminada; no se sustituye por una tecla global ni se presenta como soportado.

VLC, IINA, podcasts y otros servicios pueden añadirse después mediante adaptadores. Netflix, Prime Video, Disney+ y otros reproductores con restricciones específicas necesitan su propio estudio y no forman parte de esta cobertura inicial.

## Cómo se controlaría Chrome

El diccionario público de Chromium expone ventanas y pestañas con ID, título y URL, y un comando para ejecutar JavaScript sobre una pestaña determinada. Esto permite seleccionar el destino sin depender de cuál está activa.

Para leer/controlar el reproductor se necesita autorizar la automatización de Chrome en macOS y activar en Chrome la opción equivalente a **View > Developer > Allow JavaScript from Apple Events**. La ubicación y traducción exactas se comprobarán en la versión instalada. La app explica el ajuste cuando hace falta; no cambia preferencias de seguridad automáticamente.

Detalle relevante comprobado en Chromium: el script se ejecuta en un **mundo aislado**. Puede acceder al DOM, pero no se debe basar el diseño en variables JavaScript privadas del sitio, funciones internas de YouTube ni en recuperar los manejadores registrados de Media Session. Los adaptadores usarán medios HTML y controles DOM comprobados. Se preferirán métodos y selectores semánticos a posiciones o textos traducidos.

La operación debe volver a comprobar origen e identidad del documento/medio dentro del script que actúa, para rechazar una orden que quedó antigua por una navegación concurrente. Esa guarda protege la orden en vuelo; no fija la selección a una canción o vídeo. Los IDs se tratan como valores opacos de la sesión, no como índices de posición ni como identificadores permanentes. Se buscará el ID de pestaña entre las ventanas actuales, de modo que mover o reordenar pestañas no cambie el destino.

No se activará Chrome ni se cambiará de pestaña para simular pulsaciones. Se validará el control con otras ventanas al frente. Un reproductor que necesite una interacción visible inicial se señalará como tal; la política de autoplay, una sesión cerrada, anuncios, iframes de otro origen o pestañas suspendidas pueden impedir órdenes o lectura de estado.

## Acciones y estado

Cada fuente informa capacidades por separado: reproducir, pausar, alternar, anterior y siguiente. Los botones no admitidos quedan deshabilitados con una explicación breve.

- En Spotify/Música, anterior y siguiente significan pista anterior/siguiente.
- En YouTube, significan vídeo anterior/siguiente cuando existe esa acción en el reproductor. No equivalen a retroceder página ni a adelantar segundos. Si no hay una acción verificable, el botón se deshabilita.
- En reproducción en directo y anuncios, las capacidades pueden cambiar; no se salta contenido por una heurística genérica.
- PLAY y PAUSE dirigidos serán idempotentes cuando el adaptador los soporte. No se simulan con una tecla global de alternancia.
- Un envío aceptado no demuestra cambio de reproducción. La respuesta distingue orden enviada de estado observado y vuelve a leer el estado cuando sea posible. Un estado desconocido o antiguo se muestra como tal.

En Spotify, la app puede reflejar una sesión de Spotify Connect que reproduce en otro equipo. Elegir «Spotify · app» controla esa sesión según su comportamiento; no garantiza salida de audio local ni cambia el dispositivo de Connect. No se añadirá OAuth ni una API de cuenta en esta entrega.

## Estado compartido, actualización y errores

La sesión compartida mantendrá catálogo, destino seleccionado, capacidades, instante de observación y una revisión de la selección. Se conservará la selección mientras viva la sesión, incluso al rotar la pantalla o usar la notificación. Al cambiar a otra Mac, reiniciar el proceso o reiniciar Chrome, se parte de Automático: no se restaura una pestaña por título o URL.

El descubrimiento se ejecutará al abrir el selector, al actualizar manualmente y después de reconectar con el selector visible. Se refrescará la fuente seleccionada tras una orden, sin sondeo permanente ni inspección continua en segundo plano. Al reabrir la app se valida de nuevo la selección conservada antes de habilitar órdenes. Cada comando valida el destino justo antes de actuar.

Cada acción captura dispositivo, generación de conexión, identidad de fuente y revisión de selección. Los resultados antiguos se descartan. Si el usuario cambia de fuente mientras hay órdenes pendientes, se cancelan las que todavía no se enviaron; una ya ejecutada no se puede deshacer. No se reintenta automáticamente play/pausa o siguiente tras un corte, porque puede duplicar la acción.

Una app sin permisos no impide mostrar/controlar las demás. Se distinguirán permisos de macOS, JavaScript de Chrome deshabilitado, fuente cerrada, medio no disponible, acción no admitida y fallo de transporte. Los títulos y URLs se serializan como datos; no se interpolan como código ejecutable. Los scripts y órdenes proceden exclusivamente del catálogo interno.

La notificación usará el mismo destino y mostrará el servicio elegido, sin exponer por defecto títulos de contenidos. Para cambiarlo se abre la app. Su acción multimedia incluirá la revisión de la selección: un botón de una notificación antigua se rechaza si cambió la fuente. El servicio observará también los cambios de selección para actualizarse. No habrá descubrimiento periódico desde la notificación.

## Integración necesaria en el proyecto

| Área | Cambio previsto tras aprobación |
| --- | --- |
| Nuevo modelo y adaptadores multimedia de Mac | Identidad, capacidades, estado observado, catálogo y errores tipados |
| `MacScripts.kt` | Separar descubrimiento, lectura y control dirigido; conservar Automático explícito |
| `MacSshClient.kt` | Reutilizar SSH y añadir límite real de duración/cancelación por canal |
| `UnifiedTvController.kt` | Ejecutar órdenes con fuente y revisión; descartar resultados obsoletos |
| `RemoteSession.kt` | Poseer la selección compartida y su ciclo de vida |
| `MainViewModel.kt`, `MainScreen.kt`, `MacRemotePanel.kt` | Selector, estados de configuración y botones por capacidad |
| `QuickRemoteManager`, `QuickRemoteService`, `QuickRemoteNotification` | Reflejar selección y rechazar acciones multimedia antiguas |
| `docs/MAC.md` y pruebas | Configuración y cobertura del nuevo comportamiento |

El timeout actual de `MacSshClient.exec()` se calcula después de `readBytes()`: no acota una lectura bloqueada. Además, el cliente serializa las operaciones con un lock. Antes de añadir descubrimiento, se necesita un plazo que cubra envío y lectura, cierre del canal al cancelar y límites de respuesta. El descubrimiento será acotado y sin acumulación de consultas; no deberá monopolizar la sesión ni dejar el volumen bloqueado indefinidamente. La estrategia concreta de lectura y los límites de lotes quedan al implementador.

No se necesita migrar la base de dispositivos para guardar IDs efímeros de pestañas. No se modifica el enrutamiento de TVs Samsung o Android TV.

## Validación antes de considerar la implementación terminada

Pruebas automatizadas de lógica y transporte: filtrado de hosts/rutas, exclusión de sitios ajenos e incógnito, identidades duplicadas, serialización, capacidades, fallos parciales, cambios de destino en vuelo, notificaciones antiguas, timeouts y cancelación con SSH simulado. Pruebas de interfaz para selector y acciones deshabilitadas. Compilación, suite de regresión y lint conforme al proyecto.

Pruebas reales imprescindibles en Mac:

1. Spotify nativo y un YouTube abierto simultáneamente: seleccionar cada uno y comprobar que solo ese recibe play/pausa.
2. Dos pestañas de YouTube en ventanas diferentes: seleccionar una, reordenarla/moverla y comprobar la identidad; comprobar también títulos iguales.
3. Detectar un vídeo pausado y omitir páginas de inicio/búsqueda sin reproductor.
4. Probar por separado los cinco destinos iniciales y registrar qué acciones cumplen. Incluir YouTube Music y Spotify web sin usar funciones privadas del sitio.
5. Cerrar la fuente, navegar a otro servicio, suspender la pestaña y reiniciar Chrome: no actuar sobre un destino distinto. Cambiar de contenido dentro del mismo servicio conserva la selección, pero rechaza órdenes anteriores al cambio y actualiza capacidades.
6. Denegar Automatización, desactivar JavaScript por Apple Events y probar autoplay/estado desconocido: mostrar el motivo sin perder volumen general.
7. Controlar con Chrome en segundo plano sin cambiar foco; comprobar anterior/siguiente cuando hay cola y cuando no hay.
8. Cambiar fuente y actuar desde la notificación; rechazar botones y respuestas anteriores a la selección. Probar reconexión sin repetir órdenes.
9. Verificar que una consulta bloqueada termina, permite recuperar los controles y no rompe la conexión de otros dispositivos.

No se dispone de una Mac conectada en este entorno Linux. Estas pruebas reales no se ejecutaron durante el informe y no pueden sustituirse por el simulador SSH.

## Alcance de un eventual OK

Un OK a esta propuesta permitiría preparar el plan e implementar el selector mediante SSH, Spotify/Música nativos, los tres adaptadores Chrome propuestos, integración con la notificación y manejo de errores. La primera comprobación técnica sería validar el control dirigido real de Chrome y sus permisos. Si una limitación material obliga a instalar una extensión/agente o a reducir los cinco destinos, se volvería al usuario con evidencia antes de cambiar ese alcance.

Quedan al implementador los nombres internos, estructura de archivos, selectores concretos por servicio, límites de consulta y detalles visuales consistentes con la app. No quedan delegadas la instalación de nuevos componentes, el cambio silencioso de fuente ni la promesa de soporte sin validación.

## Evidencia y referencias

Revisión local del estado disponible de OmniRemote, HEAD `392c084`:

- `docs/MAC.md` y `app/src/main/java/com/example/network/MacScripts.kt`: transporte, permisos y heurística multimedia actual.
- `app/src/main/java/com/example/network/MacSshClient.kt`: ejecución serializada, lectura y manejo de errores.
- `app/src/main/java/com/example/ui/components/MacRemotePanel.kt`: controles actuales.
- `app/src/main/java/com/example/controller/RemoteSession.kt` y `app/src/main/java/com/example/controller/UnifiedTvController.kt`: sesión compartida y protección por generación de dispositivo.
- `app/src/main/java/com/example/service/QuickRemoteManager.kt`, `QuickRemoteService.kt` y `QuickRemoteNotification.kt`: notificación y validación actual de acciones.
- `app/src/test/java/com/example/MacRemoteTest.kt`: alcance del simulador existente.

Fuentes técnicas públicas consultadas el 2026-11-08:

- [Diccionario AppleScript de Chromium](https://github.com/chromium/chromium/blob/main/chrome/browser/ui/cocoa/applescript/scripting.sdef): ventanas, modo incógnito, IDs de ventana/pestaña, título, URL y ejecución JavaScript dirigida.
- [Implementación de ejecución JavaScript de Chromium](https://github.com/chromium/chromium/blob/main/chrome/browser/ui/cocoa/applescript/tab_applescript.mm): comprobación de permiso por perfil y `ExecuteJavaScriptInIsolatedWorld` para AppleScript.

El código de Chromium justifica el mecanismo propuesto; no certifica la versión de Chrome instalada en la Mac del usuario ni la compatibilidad actual de cada web.

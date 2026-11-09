# «Mostrame esto»: mostrar la fuente elegida en la Mac

Pedido: llevar al frente la app o pestaña seleccionada en Controlar, reutilizando
la identidad del #45 y la sesión SSH. El checkout incluye los PR #45–48.

## Alcance

- Botón «Mostrame esto» junto al selector, separado de elegir y de reproducir.
- Spotify/Música: comprobar el proceso elegido y activar esa app abierta.
- Chrome: localizar el ID de pestaña entre las ventanas normales actuales,
  comprobar proceso y servicio, seleccionar la pestaña en su ventana actual,
  restaurar esa ventana si está minimizada y llevarla al frente.
- No reproducir, pausar, abrir URLs, lanzar apps cerradas ni enviar teclas globales.
- Automático no fija una fuente: botón deshabilitado con indicación de elegir una.
- Fuente desaparecida/cambio de servicio/reinicio: explicar sin redirigir a otra.
- Cancelar órdenes pendientes al cambiar selección, dispositivo o conexión;
  compartir exclusión de operaciones con las órdenes multimedia actuales.
- Mostrar no requiere capacidades de reproducción, pista vigente ni JavaScript
  por Apple Events: alcanza con validar la identidad desde el host JXA.
- Conservar las notificaciones de #47–48 y la cola de enlaces de #46.

## Enfoques

Decisión de implementación: agregar una operación `reveal` al host JXA existente.
Reutiliza las guardas de proceso, pestaña y servicio. Abrir la URL de nuevo pierde
la pestaña exacta; activar solo Chrome pierde la ventana y pestaña elegidas.
No hacen falta un agente, extensión, dependencias ni permisos de Accesibilidad.
Automatización de macOS sigue siendo necesaria.

Las propiedades editables `active tab index`, `minimized` e `index` están
declaradas en el [diccionario de Chromium](https://github.com/chromium/chromium/blob/main/chrome/browser/ui/cocoa/applescript/scripting.sdef).

## Verificación

Harness JXA con pestañas de igual título, movidas/reordenadas, ventana minimizada,
reinicio, cierre, cambio de servicio y permisos; sin ejecutar JavaScript web ni
modificar reproducción. Pruebas del controlador para exclusión/cancelación,
rechazo de Automático y errores; Compose para botón y disponibilidad. Ejecutar
suite Android, lint y APK. Validación física en macOS queda pendiente.

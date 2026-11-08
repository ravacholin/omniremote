# Pon esto en el Mac

Recibir enlaces de vídeos, webs y mapas desde Compartir de Android. Ofrecer «Abrir» y «Guardar para después» con selección explícita de un Mac guardado. Guardar debe funcionar desconectado. Persistir una cola FIFO por ID estable del Mac; nunca enviarla a una TV u otro equipo. El usuario confirmó «Abrir siguiente» manual en orden, sin avance automático al terminar vídeos.

## Enfoque

Reutilizar SSH con clave e identidad fijada y `NSWorkspace.openURL` de macOS mediante un script enviado por stdin. No instalar software en el Mac ni nuevas dependencias. Una cola Room con migración conservadora conserva orden y aislamiento, permite consultar/borrar elementos y elimina únicamente después de éxito. Una actividad independiente de recepción añadiría sesiones y coordinación innecesarias: usar MainActivity y un diálogo de Compose. Una lista solo en memoria perdería enlaces al cerrar la app: descartada.

## Comportamiento y límites

Aceptar ACTION_SEND text/plain, text/html y text/uri-list con EXTRA_TEXT (enlace solo o rodeado de texto) y geo: convertido a Apple Maps. Validar http/https, host y ausencia de credenciales/caracteres de control. Rechazar otros esquemas, adjuntos y texto sin enlace con un mensaje visible. Recibir nuevos intents incluso con la app abierta y no repetir al rotar. Nunca ejecutar automáticamente al recibir.

Guardar en el Mac elegido sin exigir conexión; abrir exige conexión autenticada al mismo Mac. Abrir un compartido lo guarda primero y lo conserva si el envío falla. Cola FIFO; abrir siguiente procesa solo la cabecera, excluye pulsaciones simultáneas y elimina únicamente el elemento confirmado. Al cambiar de destino, cancelar cualquier orden aún no enviada. Si ya se entregó al Mac original, su cola se actualiza allí, nunca en el nuevo destino. Borrado del Mac elimina su cola por clave foránea.

El navegador predeterminado decide reproducción/autoplay. El éxito confirma la apertura por macOS, no que la página haya cargado ni que el vídeo se reproduzca. Verificación local mediante Robolectric/Room/Compose y servidor SSH simulado; apertura física requiere un Mac real.

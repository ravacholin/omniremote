# Mini mando en notificaciones

Aprobado por el usuario el 2026-11-07 tras revisar viabilidad, límites y alcance.

## Objetivo y comportamiento
Mostrar una notificación silenciosa al abrir OmniRemote y conectar con el dispositivo activo. Volumen +/−, mute, play/pause, encender/apagar y cerrar; vista compacta y expandida para respetar el espacio de Android. El nombre y las órdenes siguen al dispositivo seleccionado. Tocar el cuerpo abre la app.

Cerrar o descartar oculta el mando hasta una nueva entrada real a la app. Una rotación o reconexión no lo reactiva. Cerrar libera la conexión si la app está en segundo plano; con la app visible, su mando sigue funcionando. Apagar la TV conserva el acceso de encendido. No iniciar al arrancar el teléfono ni recrear automáticamente tras muerte del proceso.

## Enfoques
Una notificación con conexiones por pulsación simplifica el ciclo de vida pero demora cada orden. Se aprobó un servicio foreground de tipo connectedDevice: comparte una única sesión con la interfaz, reutiliza credenciales y evita sockets duplicados. No se usará mediaPlayback ni dataSync para mantener el mando.

## Restricciones
Android minSdk 24, targetSdk 36. Solicitar POST_NOTIFICATIONS en API 33+ una vez al conectar; si se deniega, conservar el mando de la app sin arrancar el servicio. Notificación de baja importancia, sin sonido ni vibración. No mantener wake locks permanentes ni sondear constantemente. Encendido depende del dispositivo y su configuración de red. Mac conserva el significado actual de POWER: dormir pantalla. El estado de reproducción de las TVs es estimado y se comparte, sin presentarlo como confirmado.

## Integración y decisiones de implementación
RemoteSession a nivel de aplicación posee controlador, selección, reproducción y encendido. MainViewModel conserva la interfaz existente y delega en ella. QuickRemoteManager gobierna apertura/cierre y autorización del servicio. Cada acción identifica la sesión y el destino para rechazar botones antiguos. El servicio no es exportado y usa PendingIntents inmutables. No reenviar teclas tras reconexión, especialmente POWER. Cancelar operaciones de encendido al cambiar de destino o cerrar en segundo plano.

## Validación
Pruebas Robolectric del cierre, reapertura, rotación, permisos, destino antiguo, compartición entre ViewModels y servicio, notificación y envío real de teclas a transporte simulado. Compilar APK y ejecutar suite existente y lint. Comprobar en dispositivo físico presentación, suspensión del teléfono y encendido real; el entorno no demuestra el firmware ni resuelve el diagnóstico Samsung pendiente.

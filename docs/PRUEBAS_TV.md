# Validación de pairing y Wake-on-LAN

## Pruebas automatizadas

Desde el entorno de desarrollo:

```bash
source /workspace/.tooling/env.sh
gradle --console=plain --no-daemon :app:assembleDebug :app:testDebugUnitTest
```

Las pruebas cubren aislamiento de tokens Samsung, cambios de IP, identidad TLS persistente,
validez de certificados, negociación Remote v2, respuestas de mantenimiento, escrituras
simultáneas, limpieza de sesiones antiguas y recepción UDP del paquete mágico de 102 bytes.
El servidor TLS de prueba simula la TV; estas pruebas no demuestran el encendido de un
televisor físico.

## Comprobación con una TV real

1. Instalar la actualización conservando los datos de la aplicación. Vincular la TV si nunca
   estuvo autorizada y comprobar navegación y volumen.
2. Forzar el cierre y volver a abrir la aplicación. Comprobar que controla la misma TV sin
   volver a solicitar autorización. Repetir después de reiniciar el teléfono.
3. Mantener la conexión varios minutos y comprobar que el mando continúa funcionando.
   Interrumpir y restaurar el Wi-Fi: la reconexión debe reutilizar las credenciales.
4. Con la TV encendida, verificar que se guarda su MAC real. Si no se puede detectar,
   introducir la MAC de la conexión que utiliza la TV (Ethernet o Wi-Fi).
5. Poner la TV en espera y probar «Encender» desde la aplicación y desde el acceso rápido.
   Confirmar que no se vuelve a apagar después de despertar y que puede controlarse al arrancar.
6. Con varios televisores, cambiar entre ellos y verificar que cada Samsung conserva su
   propia autorización. Probar también después de cambiar la IP y volver a descubrir la TV.
7. En Samsung, repetir tres ciclos de apagar y encender desde el teléfono. Durante el
   arranque, pulsar navegación/volumen: no deben abrir otro intento ni dejar POWER en
   cola. Si aparece el permiso inicial, actualizar la MAC o volver a seleccionar la
   misma TV y aceptarlo: debe conservarse esa sesión y su autorización.
8. Después de usar Sleep en Samsung, volver a encender y comprobar la reconexión sin
   autorizar otra vez. Repetir tras cerrar y volver a abrir la aplicación, conservando
   sus datos. Esto verifica que se usa el último token guardado, también si lo renovó
   la sesión de Sleep.

Para despertar, la TV necesita soporte de WoL/WoWLAN y la opción de encendido por red
activada. Teléfono y TV deben tener conectividad local; el aislamiento de clientes del router
puede impedir el envío. «Señal enviada» confirma el envío de paquetes, no que la TV esté encendida.

La renovación de certificados antiguos mantiene la misma clave RSA. Algunos firmwares pueden
exigir una vinculación inicial después de renovar un certificado que ya estaba caducado.


## Samsung UN50RU7100GCFV: permiso repetido después de POWER

La causa de este caso físico todavía no está demostrada. No se atribuye a Sleep:
el usuario no lo utiliza en Samsung. La compilación de diagnóstico conserva el
nombre OmniRemote y las credenciales existentes.

La captura se guarda localmente y se copia desde el administrador de dispositivos,
pestaña **Settings**, botón **Copiar diagnóstico Samsung**. Incluye inicio de
conexión, huella de la credencial enviada/recibida/guardada, confirmación o rechazo
explícito de Samsung, POWER, solicitud WOL y cierre o límite de espera. Las huellas
usan HMAC con clave aleatoria privada de la instalación; el informe no incluye
el token ni la clave. El registro es acotado a 80 eventos y sus escrituras son
asíncronas para no bloquear el control.

Para obtener evidencia del caso informado, mantener los datos de la aplicación:
controlar la TV, apagar con POWER, esperar el intervalo habitual, abrir OmniRemote
y encender mediante WOL. Si vuelve a aparecer el permiso, copiar el diagnóstico
antes de repetir intentos o cambiar autorizaciones. Comparar la huella del último
token guardado con la del siguiente inicio y revisar la respuesta real de la TV.
El informe no prueba que el firmware conserve autorizaciones; permite distinguir
pérdida local, credencial equivocada, rechazo de Samsung y fallos de transporte.

Las regresiones locales cubren cierre normal y vuelta al primer plano, callbacks
terminales, anuncios de otros clientes, conservación de conexiones pendientes,
reconexión iniciada por teclas y cancelación de deadlines. La prueba de integración
usa TLS y WebSocket reales contra una TV simulada y recorre cinco ciclos de POWER,
cierre y recreación del cliente, renovando el token en cada conexión.

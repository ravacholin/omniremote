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

Para despertar, la TV necesita soporte de WoL/WoWLAN y la opción de encendido por red
activada. Teléfono y TV deben tener conectividad local; el aislamiento de clientes del router
puede impedir el envío. «Señal enviada» confirma el envío de paquetes, no que la TV esté encendida.

La renovación de certificados antiguos mantiene la misma clave RSA. Algunos firmwares pueden
exigir una vinculación inicial después de renovar un certificado que ya estaba caducado.

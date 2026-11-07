# Sleep flexible

El usuario aprobó implementar la propuesta de mantener el toque rápido de
30 → 60 → 120 → OFF, añadir duración/hora mediante pulsación larga y sumar
15 minutos al vencimiento existente.

Se amplía SleepTimerManager y su alarma exacta persistida. Una segunda alarma
o un servicio permanente duplicaría la gestión de cancelación y recuperación;
se conserva una sola generación y se reemplaza su token al modificar el plazo.

La base final incluye main 6445cf4 (PR #43): se conserva el mini mando en
notificaciones y su sesión compartida, manteniendo independiente la alarma Sleep.

- Pulsación larga: diálogo para la TV seleccionada, con duración de 1 a 1440
  minutos o una hora de 24 horas. La hora se interpreta en la zona local del
  teléfono, en su próxima ocurrencia; se muestra hoy/mañana y la fecha/hora.
- Una duración/hora personalizada reinicia el próximo toque rápido en 30 minutos.
  Extender un preset conserva su posición en el ciclo rápido.
- El aviso activo ofrece +15 min y Cancelar. La extensión añade exactamente
  900000 ms al vencimiento guardado, conserva la TV y exige el token vigente.
  No extiende temporizadores vencidos ni apagados en ejecución.
- La entrada se valida antes de cambiar la alarma. Cancelar el diálogo no cambia
  nada. El diálogo se descarta al cambiar/eliminar la TV. El regreso desde ajustes
  conserva la solicitud y vuelve a validar su destino/token y la hora elegida.
- Se mantienen permisos, persistencia, invalidación tras reinicio, credenciales,
  identidad y verificación del apagado. No se modifica ningún protocolo remoto.

Decisiones de implementación: límites 1–1440, reloj de 24 horas, próxima ocurrencia
y reinicio del ciclo tras personalizar son decisiones del implementador. No se
añaden recurrencias, múltiples timers, apagado del Mac ni cambios de volumen.

Validación: pruebas Robolectric de alarmas/persistencia/carreras y Compose de
pulsación larga, validación, selección de hora, extensión y permisos; suite JVM,
lint y APK debug. Las comprobaciones locales no sustituyen probar una TV física.

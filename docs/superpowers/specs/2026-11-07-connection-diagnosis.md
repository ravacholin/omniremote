# Diagnóstico de conexión

Implementar «¿Por qué no conecta?» en el mando para el equipo seleccionado.
Mostrar red local, respuesta del servicio e identidad/dirección, y autorización,
con estados explícitos de comprobado, atención y no comprobado. No requiere internet.

Se reutilizan TvLocalNetwork, TvScanner, ConnectionState, TvTokenStore y los eventos
Samsung existentes. Una credencial guardada no demuestra una autorización vigente;
un puerto abierto tampoco. Los registros antiguos se presentan como antecedentes.

La búsqueda propone actualizar la IP únicamente con una coincidencia MAC válida,
única y del mismo tipo de equipo. Sin identidad verificable se ofrece revisar el
equipo manualmente. No se identifica por nombre. El cambio de IP actualiza la fila
existente y conserva ID, apps, temporizadores y credenciales. No borra ni desvincula.

Se descarta el trabajo al cerrar, seleccionar/eliminar otro equipo o cambiar su IP.
Las acciones vuelven a comprobar su destino; actualizar IP usa comparación de la
dirección anterior y no pisa datos concurrentes. Buscar o diagnosticar no autoriza
automáticamente ni envía teclas. Reintentar usa el flujo de conexión existente.

Alternativas consideradas: explicar solo registros (no detecta cambios actuales),
rehacer conexión y pairing (arriesga sesiones pendientes), comprobar red y descubrir
de forma pasiva con acciones explícitas (elegida por encajar en el pedido).

Validación: pruebas de lógica, registros, persistencia y Compose; compilación APK.
La red y los permisos del equipo físico requieren validación con el dispositivo.

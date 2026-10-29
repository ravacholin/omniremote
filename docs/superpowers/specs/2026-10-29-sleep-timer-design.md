# Sleep

El botón recorre 30 → 60 → 120 → OFF. Cada toque reinicia la cuenta completa con el nuevo valor; OFF cancela el apagado. Al vencer se envía una orden de apagado explícita, sin Wake-on-LAN, a la TV elegida al programar el temporizador. Cambiar de TV no modifica el destino; volver a programar Sleep usa la TV seleccionada en ese momento. Hay un solo temporizador.

Se aplica a Samsung Tizen y Google/Android TV, los dos tipos de TV que admite el proyecto. Mac conserva sus controles actuales. El botón muestra el valor y un aviso confirma cada toque. El temporizador activo identifica su TV y permite cancelarlo incluso después de cambiar de dispositivo.

Se usará AlarmManager con alarma exacta que puede despertar el teléfono y un servicio breve de primer plano para reconectar y enviar el apagado. Un delay en el ViewModel se perdería al cerrar la app; una alarma aproximada puede retrasarse con el teléfono bloqueado. La elección de AlarmManager es de implementación. Android 12+ puede requerir habilitar «Alarmas y recordatorios» antes de activar Sleep. No se mostrará un timer activo si el sistema no pudo programarlo.

Se guardan identidad de la TV, minutos, vencimiento monotónico e identificador único. Cada reprogramación invalida la anterior; una entrega antigua o duplicada no puede apagar otra TV. Se reutilizan las credenciales existentes. Eliminar la TV cancela su temporizador. Reiniciar el teléfono cancela Sleep; no se restauran apagados desde una copia de seguridad. Sin red o autorización, se informa del fallo, sin encender la TV ni intentar vincularla automáticamente.

Confirmación del usuario: los toques avanzan al siguiente valor y reinician la cuenta con ese tiempo. No hay dispositivos físicos conectados para verificar el apagado real.

# Corrección de conteos — 11 de octubre de 2026

Los cambios locales del backend corrigen estos casos:

- Una entrega/devolución repetida o una corrección de mensajero/subtipo conserva la fecha original.
- Repetir un restablecimiento al mismo estado no agrega un movimiento.
- Los cambios de estado bloquean la fila mientras dura la transacción y los lotes normalizan y deduplican trackings.
- El dashboard, los reportes diarios y los mensajeros cuentan paquetes con estado vigente, excluyendo entregas revertidas. Los movimientos permanecen disponibles en el historial.
- Las fotografías históricas usan la fecha de cada transición real, sin reemplazarla por la fecha de la última entrega ni contar cambios de ubicación como cambios de estado.

Para aplicar también la corrección de importaciones en una instancia existente, respaldar la base y ejecutar `migrations/20261011_conteo_movimientos.sql` con la base correcta seleccionada. Luego desplegar el backend corregido. El script redefine tres procedimientos; no borra ni modifica datos históricos. No se ha ejecutado sobre Railway.

Las fechas antiguas ya alteradas no se reconstruyen automáticamente: requieren contrastarlas con el historial y los documentos originales. La clasificación por subtipo de fechas pasadas conserva la limitación del esquema original, que no guarda el subtipo en cada movimiento.

Pruebas: `mvn test` desde `backend`. Se agregaron casos para repeticiones, reversión de entrega, reasignación, lotes duplicados y fechas históricas.

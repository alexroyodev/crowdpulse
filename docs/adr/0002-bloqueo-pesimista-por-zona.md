# ADR 0002 — Bloqueo pesimista por zona (`SELECT ... FOR UPDATE`)

- **Estado:** Aceptada
- **Fecha:** 2026-10-19 (decidida en el plan 001), redactada 2026-10-21
- **Spec:** [001 — Ingesta MVP](../../specs/001-ingesta-mvp/spec.md)

## Contexto
Cada evento lee la ocupación de su zona, calcula la nueva (`Occupancy.apply`, dominio
puro) y la guarda. Si dos peticiones de la misma zona hacen eso a la vez, las dos leen
el mismo valor y una actualización se pierde. La spec exige que no ocurra (regla 4,
CA6: 1 000 `ENTRY` desde 20 hilos → exactamente 1 000).

La regla del suelo en 0 (regla 5: un `EXIT` con la zona vacía se queda en 0 y se marca
como anomalía) debe seguir en el dominio, probada sin base de datos.

## Decisión
`EventService.accept` se ejecuta en una transacción que:

1. bloquea la fila de la zona con `SELECT ... FROM zone_occupancy WHERE zone_id = ? FOR UPDATE`;
2. calcula el cambio con `Occupancy.apply`;
3. inserta el evento con `ON CONFLICT DO NOTHING` (si es duplicado, termina aquí);
4. actualiza la ocupación.

El bloqueo serializa los eventos de **una misma zona**; zonas distintas no se esperan
entre sí. Se bloquea antes de insertar para que dos copias simultáneas del mismo evento
también queden en fila. Aislamiento `READ COMMITTED` (el de PostgreSQL por defecto).

## Alternativas descartadas
- **Bloqueo optimista** (columna `version` y reintentos). En una zona con miles de
  eventos seguidos los conflictos serían constantes, y habría que escribir, explicar y
  probar un bucle de reintentos.
- **`UPDATE ... SET occupancy = occupancy + 1` atómico.** Correcto y más rápido, pero la
  regla del suelo en 0 y la detección de anomalía pasarían al SQL
  (`GREATEST`, `RETURNING`...) y ya no se podrían probar sin base de datos.
- **Aislamiento `SERIALIZABLE`.** Detecta el conflicto, pero lo resuelve abortando
  transacciones: volvemos a necesitar reintentos.

## Evidencia
- `ConcurrentIngestionIT` (CA6) pasa de forma estable (3 ejecuciones seguidas en verde).
- **Prueba de mutación:** quitando el `FOR UPDATE`, el mismo test termina con
  **169** en lugar de 1 000. El test detecta de verdad las actualizaciones perdidas.
- Los duplicados concurrentes (el mismo evento desde 20 hilos) los protege la clave
  primaria con `ON CONFLICT`, no el bloqueo: ese test pasa también sin `FOR UPDATE`.
- Demo con el simulador: 100 ev/s durante 60 s sin ningún error.

## Consecuencias
- ✅ La lógica de negocio sigue en `Occupancy`, probada con tests unitarios.
- ✅ Es el mismo modelo de orden que tendremos con Kafka en la fase 2: un consumidor
  por partición y clave de mensaje = `zoneId`.
- ⚠️ El rendimiento de una zona está limitado a una transacción cada vez. Para la fase 1
  sobra; no está pensado para miles de eventos por segundo en una sola zona.
- ⚠️ Toda operación que bloquee varias zonas tendría que hacerlo siempre en el mismo
  orden para no provocar interbloqueos. Hoy cada evento bloquea una sola zona.

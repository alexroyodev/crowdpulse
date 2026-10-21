# ADR 0003 — El nivel de alerta se decide en la transacción del evento

- **Estado:** Aceptada
- **Fecha:** 2026-10-21
- **Spec:** [002 — Alertas por umbrales](../../specs/002-alertas-umbrales/spec.md)

## Contexto
La spec 002 añade niveles de alerta por zona (`NORMAL`, `WARNING`, `CRITICAL`) con
histéresis, y un histórico de cambios de nivel. Exige que:

- evento, ocupación y cambio de nivel se guarden juntos o ninguno (regla 7, CA6);
- peticiones concurrentes que cruzan un umbral lo registren **una sola vez** (regla 8, CA7);
- la lógica de umbrales e histéresis se pueda probar sin base de datos.

La spec 001 ya procesa cada evento en una transacción que bloquea la fila de
`zone_occupancy` de su zona con `FOR UPDATE` ([ADR 0002](0002-bloqueo-pesimista-por-zona.md)).

## Decisión
1. El nivel se decide **dentro de esa misma transacción**, en `EventService.accept`,
   después de calcular la nueva ocupación: `AlertPolicy.next(nivel actual, ocupación,
   capacidad)`, una función pura con comparación entera exacta.
2. El nivel actual se guarda en una columna nueva de **la misma fila ya bloqueada**
   (`zone_occupancy.alert_level`), en el mismo `UPDATE` que la ocupación.
3. Si el nivel cambia, se inserta una fila en `alert` en la misma transacción.
   `event_id UNIQUE` impide en la base de datos dos cambios por un mismo evento.

## Alternativas descartadas
- **Evaluar las alertas después del commit**, con un listener
  (`@TransactionalEventListener`). Separa mejor las responsabilidades, pero si la
  aplicación cae entre el commit y la alerta, la ocupación queda guardada y la alerta
  se pierde: rompe la regla 7.
- **Deducir el nivel actual del último registro de `alert`.** Obliga a consultarlo en
  cada evento, una zona sin historial necesita un caso aparte, y la consulta no queda
  protegida por el bloqueo de la fila de ocupación.
- **Comparar porcentajes con `double` o con el valor redondeado.** 16 999 de 20 000 se
  muestra como `85.0` pero es 84,995 %: la comparación debe ser exacta
  (`ocupación × 100 ≥ 85 × capacidad`).

## Evidencia
- `AlertPolicyTest` recorre la tabla de ejemplo de la spec. Sin histéresis (salir en el
  mismo umbral de entrada) fallan 4 tests, entre ellos el de oscilación.
- `AlertAtomicityIT` (CA6): sin `@Transactional`, el evento queda guardado aunque falle
  el registro de la alerta.
- `ConcurrentAlertIT` (CA7): 20 `ENTRY` a la vez a través del 85 % dan exactamente un
  `NORMAL → WARNING`, en 3 ejecuciones seguidas. Sin `FOR UPDATE`, de las 20 entradas
  solo cuentan 2.
- Demo: `--zones=PALCO --rate=20 --duration=60s` → `NORMAL → WARNING` (170) y
  `WARNING → CRITICAL` (190).

## Consecuencias
- ✅ Las garantías de la spec 002 se apoyan en las de la spec 001, sin mecanismos nuevos.
- ✅ Cuando llegue Kafka (fase 2), el consumidor de cada partición hará lo mismo dentro
  de su transacción.
- ⚠️ Cada evento que cambia el nivel hace un `INSERT` más dentro de la transacción. Es
  infrecuente: solo ocurre al cruzar un umbral.
- ⚠️ Los umbrales 85 y 95 aparecen también en la migración V3, solo para calcular el
  nivel inicial (regla 11). Si cambian, cada zona se corrige con su siguiente evento.

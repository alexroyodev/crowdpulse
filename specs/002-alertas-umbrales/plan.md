# Plan 002 — Alertas de ocupación por umbrales

- **Estado:** Borrador, pendiente de revisión
- **Spec:** [`spec.md`](spec.md)
- **Fase:** 1
- **Fecha:** 2026-10-21

Cómo se implementa la spec 002 sobre lo que dejó la 001. Cada decisión no obvia lleva
la alternativa descartada. Las marcadas con ⚠️ necesitan tu aprobación (esquema de BD).
No hay dependencias nuevas ni módulos nuevos.

---

## 1. Idea central
El nivel de alerta se decide **dentro de la transacción que ya existe** en
`EventService.accept`, justo después de calcular la nueva ocupación, y se guarda en la
**misma fila de `zone_occupancy` que ya está bloqueada** con `FOR UPDATE`. Así:

- la atomicidad (regla 7, CA6) sale de la misma transacción de la spec 001;
- la concurrencia (regla 8, CA7) sale del mismo bloqueo por zona: dos peticiones de la
  misma zona nunca evalúan el nivel a la vez, así que no pueden registrar el mismo
  cruce dos veces;
- la lógica de umbrales e histéresis es una función pura, probada sin Spring.

```
EventService.accept  [@Transactional]
  1. SELECT occupancy, last_event_at, alert_level, capacity ... FOR UPDATE   (ampliado)
  2. Occupancy.apply(type, occurredAt)                                        (igual)
  3. INSERT sensor_event ... ON CONFLICT DO NOTHING → duplicado: return        (igual)
  4. AlertPolicy.next(nivel actual, nueva ocupación, capacidad)               (nuevo, puro)
  5. UPDATE zone_occupancy SET occupancy, last_event_at, alert_level          (ampliado)
  6. si el nivel cambió → INSERT alert + log WARN                              (nuevo)
```

Un duplicado sale en el paso 3, así que nunca llega al 4 (CA5). Un `EXIT` anómalo no
cambia la ocupación, así que `AlertPolicy` devuelve el mismo nivel (regla 1).

*Descartado:* evaluar las alertas después del commit, en otro componente (un listener
de eventos de Spring, `@TransactionalEventListener`). Separa mejor las
responsabilidades, pero rompe la regla 7: si la aplicación cae entre el commit y la
alerta, la ocupación queda guardada y la alerta se pierde.

## 2. Esquema ⚠️ (dos migraciones nuevas; V1 y V2 no se tocan)

`V3__add_alert_level_and_history.sql`
```sql
ALTER TABLE zone_occupancy
    ADD COLUMN alert_level VARCHAR(8) NOT NULL DEFAULT 'NORMAL'
        CHECK (alert_level IN ('NORMAL', 'WARNING', 'CRITICAL'));

-- Regla 11: cada zona arranca con el nivel de su ocupación actual, sin histórico.
UPDATE zone_occupancy o
SET alert_level = CASE
        WHEN o.occupancy * 100 >= 95 * z.capacity THEN 'CRITICAL'
        WHEN o.occupancy * 100 >= 85 * z.capacity THEN 'WARNING'
        ELSE 'NORMAL'
    END
FROM zone z
WHERE z.zone_id = o.zone_id;

CREATE TABLE alert (
    seq          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_id     UUID        NOT NULL UNIQUE,
    zone_id      VARCHAR(20) NOT NULL REFERENCES zone (zone_id),
    from_level   VARCHAR(8)  NOT NULL,
    to_level     VARCHAR(8)  NOT NULL,
    occupancy    INTEGER     NOT NULL,
    capacity     INTEGER     NOT NULL,
    event_id     UUID        NOT NULL UNIQUE REFERENCES sensor_event (event_id),
    occurred_at  TIMESTAMPTZ NOT NULL,
    detected_at  TIMESTAMPTZ NOT NULL,
    CHECK (from_level <> to_level)
);
```

`V4__add_demo_zone_palco.sql`: inserta `PALCO` («Palco VIP», 200) en `zone` y su fila
en `zone_occupancy`. Va en una migración aparte porque es otro cambio (datos de demo,
D3), y así se puede leer y explicar por separado.

Decisiones:
- **El nivel actual vive en `zone_occupancy`**, al lado de la ocupación, porque es
  estado de la zona que cambia con cada evento y se lee y se escribe con el mismo
  bloqueo. *Descartado:* deducirlo del último registro de `alert` de cada zona. Habría
  que consultarlo en cada evento, y una zona sin historial tendría que tratarse aparte.
- **`event_id UNIQUE` en `alert`**: un evento provoca como mucho un cambio de nivel.
  Es una red de seguridad en la base de datos, además del bloqueo.
- **`seq` como orden**: `GET /alerts` ordena por `seq DESC`, el orden en que se
  insertaron los registros. *Descartado:* ordenar por `detected_at`. Con el `Clock` fijo
  de los tests todos los registros tienen el mismo instante y el orden quedaría
  indefinido.
- **`capacity` se copia en cada registro**: el histórico enseña el porcentaje con la
  capacidad que tenía la zona en ese momento, aunque el catálogo cambie después.
- **Los umbrales 85 y 95 aparecen en la migración V3.** Es la única copia fuera de la
  configuración y solo calcula el nivel inicial (regla 11). Si un día cambian los
  umbrales, el nivel de cada zona se corrige con el siguiente evento que reciba.
  *Descartado:* calcular el nivel inicial al arrancar la aplicación. Sería código que
  se ejecuta en cada arranque para resolver algo que solo pasa una vez.

## 3. Paquete `alert` (nuevo)

| Clase | Qué es |
|-------|--------|
| `AlertLevel` | enum `NORMAL`, `WARNING`, `CRITICAL` |
| `AlertProperties` | `@ConfigurationProperties("crowdpulse.alerts")` con `warning` y `critical`, cada uno `{enterPct, exitPct}`; valida al arrancar (salida < entrada, `WARNING` < `CRITICAL`) |
| `AlertPolicy` | función pura `next(nivelActual, ocupación, capacidad) → AlertLevel` |
| `Alert` | record del registro guardado |
| `AlertRepository` | `insert(Alert)` y la consulta del histórico (`JdbcClient`) |
| `AlertController` | `GET /api/v1/alerts?zoneId=&limit=` |
| `AlertView` | record de respuesta (con `occupancyPct` y los instantes con milisegundos, como `ZoneView`) |

### `AlertPolicy.next`: reglas 2 a 5 en orden
```
llega al umbral de entrada de CRITICAL            → CRITICAL
está en CRITICAL y no baja del umbral de salida   → CRITICAL
llega al umbral de entrada de WARNING             → WARNING   (también al bajar desde CRITICAL)
está en WARNING o CRITICAL y no baja de su salida → WARNING
en otro caso                                      → NORMAL    (puede bajar dos niveles de golpe)
```
- **Comparación exacta con enteros** (regla 2): «llega al 85 %» es
  `ocupación × 100 ≥ 85 × capacidad`, con `long`. No hay `double` ni redondeo: 16 999
  de 20 000 no llega.
- Los umbrales son porcentajes enteros, como los fija la spec.

### Configuración (`application.yml`, valores de D1)
```yaml
crowdpulse:
  alerts:
    warning:  { enter-pct: 85, exit-pct: 80 }
    critical: { enter-pct: 95, exit-pct: 90 }
```

## 4. Cambios en lo existente

| Fichero | Cambio |
|---------|--------|
| `OccupancyRepository` | `lockByZoneId` devuelve también `alert_level` y la `capacity` (`JOIN zone`) en un record `LockedZone`; `update` guarda también el nivel |
| `EventService` | pasos 4 y 6 del §1 |
| `ZoneView` / `ZoneQueryRepository` | campo `alertLevel` (lee `alert_level`); `percentage` pasa a público para reutilizarlo en `AlertView` |
| `ApiExceptionHandler` | el 400 de `limit` (`HandlerMethodValidationException` y `TypeMismatchException`) también lleva `errors: [{field, message}]` |
| `messages.properties` | textos de esas dos excepciones |
| `reset-state.sql` (test) | `DELETE FROM alert` primero (por la clave foránea) y `alert_level = 'NORMAL'` |
| `MigrationIT`, `ZoneQueryIT` | 5 zonas en lugar de 4 y el campo `alertLevel` en el JSON exacto (catálogo cambiado por D3, no son tests relajados) |

- La validación de `limit` usa `@Min(1) @Max(500)` en el `@RequestParam`. Spring MVC 7
  valida esos parámetros solo, sin `@Validated`.
- Una zona inexistente en `?zoneId=` responde **404** con la misma
  `ZoneNotFoundException` del `GET /zones/{zoneId}`.
- Log de cada cambio (CA9): `WARN "Alert level changed"` con `zoneId`, `from`, `to`,
  `occupancyPct` y `eventId`.

## 5. Tests

### Unitarios
| Test | Cubre |
|------|-------|
| `AlertPolicyTest` | La tabla de ejemplo de la spec, fila a fila (test parametrizado); 84,995 % no llega; > 100 % sigue `CRITICAL`; bajada de `CRITICAL` a `NORMAL` de golpe; misma ocupación, mismo nivel |
| `AlertPropertiesTest` | Rechaza salida ≥ entrada y `WARNING` ≥ `CRITICAL` |
| `ZoneViewTest` | Sin cambios, salvo el campo nuevo |

### Integración
El estado de partida se prepara con SQL (por ejemplo, `NORTE` a 16 999) en lugar de
enviar 17 000 eventos; luego se envía el evento que cruza el umbral por HTTP.

| Test | Criterio |
|------|----------|
| `AlertIT` | CA1, CA2, CA3 (10 oscilaciones → 1 registro), CA4, CA5, CA8 (orden, `zoneId`, `limit`, 404, 400), CA9 (log capturado con `OutputCaptureExtension`) |
| `AlertAtomicityIT` | CA6: si falla `AlertRepository.insert`, no se guardan ni el evento ni la ocupación (`@MockitoSpyBean`, como `TransactionAtomicityIT`) |
| `ConcurrentAlertIT` | CA7: `NORTE` a 16 990, 20 hilos con un `ENTRY` cada uno a la vez → 17 010 y exactamente un `NORMAL → WARNING` |
| `RestartIT` (ampliado) | CA10: `alertLevel` y el histórico iguales tras reiniciar |
| `MigrationIT` (ampliado) | V3 y V4: 5 zonas, todas en `NORMAL`; `CHECK` de `alert_level` |

**Pruebas de mutación** (constitución, principio 2), una vez cada una:
- Sin `@Transactional`, `AlertAtomicityIT` debe fallar.
- Sin la histéresis (salir al bajar del umbral de entrada), CA2 y CA3 deben fallar.
- Sin `FOR UPDATE`, `ConcurrentAlertIT` debe fallar.

### Manual
- Con `curl`: los casos de `GET /api/v1/alerts`, y `alertLevel` en `/zones`.
- **CA11:** `--zones=PALCO --rate=20 --duration=60s`. Con el perfil del simulador
  (ocupación ≈ N·(0,8x − 0,6x²) para N eventos y progreso x), 1 200 eventos llevan
  `PALCO` al 85 % hacia el segundo 12 y por encima del 95 % poco después. El
  simulador también comprueba CA10 de la spec 001 en esa zona.
- **CA12:** `./mvnw verify` desde un clon limpio.

## 6. ADR propuesto
- `docs/adr/0003-nivel-de-alerta-en-la-transaccion-del-evento.md`: por qué el nivel se
  decide y se guarda en la misma transacción y en la misma fila bloqueada (§1 y §2).

## 7. Riesgos y puntos a revisar
1. **Orden de llegada:** la alerta se calcula con la ocupación en el orden en que se
   **procesan** los eventos, como la ocupación de la spec 001. El `occurredAt` del
   registro es el del evento que provocó el cambio, aunque llegara desordenado.
2. **`PALCO` aparece para todos:** sale en `GET /zones`, pero el simulador no la envía
   por defecto, así que la demo de CA10 de la spec 001 no cambia.
3. **Sin paginación:** `GET /alerts` devuelve como máximo 500 registros. Para esta fase
   es suficiente.

# Tareas 002 — Alertas de ocupación por umbrales

- **Estado:** Aprobado (2026-10-21)
- **Spec:** [`spec.md`](spec.md) · **Plan:** [`plan.md`](plan.md)

Una tarea cada vez. Está terminada cuando su verificación pasa, `./mvnw verify` está en
verde y `MEMORY.md` está actualizado. Al terminar se marca `[x]`.

---

## Bloque A — Esquema

- [x] **T01 — Migraciones V3 y V4** (plan §2)
  - `V3__add_alert_level_and_history.sql`: columna `alert_level`, nivel inicial
    (regla 11) y tabla `alert`.
  - `V4__add_demo_zone_palco.sql`: zona `PALCO` («Palco VIP», 200) y su fila de ocupación.
  - `reset-state.sql`: `DELETE FROM alert` antes que `sensor_event`; `alert_level = 'NORMAL'`.
  - `MigrationIT`: 5 zonas (con `PALCO`), todas en `NORMAL`; un `alert_level` inválido
    falla por el `CHECK`; un segundo `alert` con el mismo `event_id` falla por `UNIQUE`.
  - `ZoneQueryIT`: el listado pasa a 5 zonas.
  - `InitialAlertLevelMigrationIT` (añadido): regla 11 con una base migrada hasta V2 que
    ya tiene ocupación.
  - **Verificación:** `./mvnw verify`; `psql` en el contenedor de compose muestra
    `alert`, la columna nueva y `flyway_schema_history` con V3 y V4.

## Bloque B — Dominio (sin Spring)

- [x] **T02 — `AlertLevel` y `AlertProperties`** (plan §3)
  - `alert.AlertLevel`; `alert.AlertProperties` (`crowdpulse.alerts.warning|critical`
    con `enterPct` y `exitPct`), validada en el constructor.
  - `application.yml` con los valores de D1 (85/80 y 95/90).
  - `AlertPropertiesTest`: valores de D1 válidos; salida ≥ entrada → error;
    `WARNING` ≥ `CRITICAL` → error.
  - **Verificación:** `./mvnw verify` (el contexto arranca con la configuración nueva).

- [x] **T03 — `AlertPolicy`** (plan §3, reglas 2–5)
  - `alert.AlertPolicy.next(current, occupancy, capacity)` con comparación entera.
  - `AlertPolicyTest`: la tabla de ejemplo de la spec, fila a fila (test parametrizado);
    16 999 / 20 000 no llega; 20 500 sigue `CRITICAL`; de `CRITICAL` a `NORMAL` de golpe;
    misma ocupación → mismo nivel.
  - **Mutación:** quitar la histéresis (salir al bajar del umbral de entrada) → el test
    falla; restaurar.
  - **Verificación:** `./mvnw test`.

## Bloque C — Integración en la API

- [x] **T04 — Evaluar y registrar el cambio de nivel** (plan §1, §4)
  - `OccupancyRepository`: `lockByZoneId` → `LockedZone` (ocupación, nivel, capacidad);
    `update` guarda también el nivel.
  - `alert.Alert` y `alert.AlertRepository.insert`.
  - `EventService`: pasos 4 y 6 del plan §1; log `WARN "Alert level changed"`.
  - `AlertIT` (comprobando la tabla `alert` con SQL): CA1, CA2, CA3, CA4, CA5 y CA9
    (log capturado).
  - **Verificación:** `./mvnw verify`; manual: con `psql` se lleva `PALCO` a 169 personas,
    un `ENTRY` por `curl` y aparece una fila en `alert` y el `WARN` en el log.

- [x] **T05 — `alertLevel` en las zonas** (plan §4, D2)
  - `ZoneView` + `ZoneQueryRepository`; `ZoneView.percentage` pasa a público.
  - `ZoneQueryIT`: JSON exacto con `alertLevel`; una zona en `WARNING` lo muestra.
  - **Verificación:** `./mvnw verify`; `curl localhost:8080/api/v1/zones`.

- [x] **T06 — `GET /api/v1/alerts`** (plan §3, §4)
  - `alert.AlertView`, consulta del histórico en `AlertRepository` (`ORDER BY seq DESC`,
    `zoneId`, `limit`), `alert.AlertController`.
  - `ApiExceptionHandler` + `messages.properties`: 400 de `limit` con `errors`.
  - `AlertIT`: CA8 (orden, filtro, `limit`, `[]` sin datos, 404 zona inexistente, 400 con
    `limit=0`, `limit=501` y `limit=abc`).
  - **Verificación:** `./mvnw verify`; `curl` de cada caso.

- [ ] **T07 — Atomicidad y concurrencia** (CA6, CA7)
  - `AlertAtomicityIT`: falla `AlertRepository.insert` → ni evento ni ocupación guardados.
  - `ConcurrentAlertIT`: `NORTE` a 16 990, 20 hilos a la vez → 17 010 y un solo
    `NORMAL → WARNING`.
  - **Mutaciones:** sin `@Transactional` → falla `AlertAtomicityIT`; sin `FOR UPDATE` →
    falla `ConcurrentAlertIT`; restaurar ambas.
  - **Verificación:** `./mvnw verify`, 3 veces seguidas en verde.

- [ ] **T08 — Persistencia tras reinicio** (CA10)
  - `RestartIT`: además de la ocupación, `alertLevel` y `GET /api/v1/alerts` son iguales
    tras reiniciar (eventos a `PALCO` hasta pasar el 85 %).
  - **Verificación:** `./mvnw verify`.

## Bloque D — Cierre

- [ ] **T09 — Demo, documentación y verificación final** (CA11, CA12)
  - Demo: `--zones=PALCO --rate=20 --duration=60s` → el histórico de `PALCO` tiene
    `NORMAL → WARNING` y `WARNING → CRITICAL`; el informe del simulador acaba en OK.
  - `docs/adr/0003-nivel-de-alerta-en-la-transaccion-del-evento.md`.
  - `README.md`: alertas, `GET /api/v1/alerts`, `PALCO` y la demo.
  - CA12: `git clone` limpio + `./mvnw verify`.
  - Tabla de verificación de los CA al final de este fichero; spec y tareas terminadas;
    `MEMORY.md` actualizado.
  - **Verificación:** todos los CA con test o verificación manual registrada.

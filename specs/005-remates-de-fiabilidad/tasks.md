# Tareas 005 — Remates de fiabilidad

- **Estado:** Terminado (2026-10-22)
- **Spec:** [`spec.md`](spec.md) · **Plan:** [`plan.md`](plan.md)

Una tarea cada vez. Está terminada cuando su verificación pasa, `./mvnw verify` está en
verde (por módulos: `-pl shared,api` y `-pl shared,processor,simulator`) y `MEMORY.md` está
actualizado. Al terminar se marca `[x]`.

---

- [x] **T01 — El `processor`, solo en local** (plan §1; CA1)
  - `server.address: ${CROWDPULSE_PROCESSOR_ADDRESS:127.0.0.1}`.
  - Tests que llamen al `processor` por HTTP: `127.0.0.1` en lugar de `localhost`.
  - `ProcessorAddressIT`: por defecto responde en `127.0.0.1` y no en la IP del equipo; con
    `0.0.0.0`, sí.
  - **Mutación:** sin `server.address` → falla; restaurar.
  - **Verificación:** `verify`; manual: `curl` a `127.0.0.1:8081` y a la IP del equipo.

- [x] **T02 — El reproceso se salta los fallos de contenido** (plan §2; CA2, CA3)
  - `ContentFailure` y su test unitario; `DeadLetterReplayer.replay(all)`;
    `?all=true` en el controlador; respuesta `{"replayed", "skipped"}`.
  - `DeadLetterReplayIT`: CA2 y CA3; los casos existentes se adaptan a la respuesta nueva.
  - **Mutación:** `isContentFailure` siempre `false` → CA2 falla; restaurar.
  - Hecho así (2026-10-22): `ContentFailure` vive en `event` y su lista (`TYPES`) la usa
    también el gestor de errores, para que «al DLT al primer intento» y «no se reprocesa»
    no puedan divergir. CA2 y CA3 están en `DeadLetterReplaySkipIT`, con sus propios
    contenedores (`?all=true` relee todo el DLT). Manual en compose: `{"replayed":0,"skipped":2}`
    y después `{"replayed":0,"skipped":0}`: los mensajes rotos ya no rebotan.
  - **Verificación:** `verify`; manual: `curl -X POST '127.0.0.1:8081/admin/dead-letters/replay'`
    con los mensajes rotos que ya hay en el DLT de compose → `skipped` > 0.

- [x] **T03 — El simulador reintenta los 503** (plan §3; CA4)
  - `ApiClient` (`Retry-After`), `ZoneRunner` (reintento con reloj y pausa inyectados),
    `ZoneResult` y `SimulationReport` (línea de reintentos).
  - `ZoneRunnerTest`: los tres casos del plan.
  - **Mutación:** sin reintentos → falla; restaurar.
  - **Verificación:** `./mvnw -pl simulator verify`.
  - Hecho así (2026-10-22): `Pause` pasa a ser una interfaz propia (la usan `SettlingWait` y
    `ZoneRunner`); un cuarto test cubre la lectura de `Retry-After` (segundos, 1 s si falta o
    es una fecha). Sin reintentos fallan los 3 tests de reintento.

- [x] **T04 — Demo, documentación y cierre** (CA5, CA6)
  - Demo del plan §4.
  - README; nota en el ADR 0005.
  - CA6: clon limpio + `docker compose up -d` + `verify` por módulos.
  - Tabla de verificación de los CA; spec y tareas terminadas; `MEMORY.md` (fase 3
    cerrada, problemas abiertos de la spec 004 resueltos).
  - Hecho así (2026-10-22): la primera demo perdió 6 eventos por «sin conexión». Con la base
    de datos caída la API tarda hasta ~6 s en dar el 503, más que los 5 s de espera del
    simulador, y un timeout no se reintenta. El simulador espera ahora 10 s.

---

## Verificación de los criterios de aceptación

| CA | Cómo se verifica | Resultado |
|----|------------------|-----------|
| CA1 | `ProcessorAddressIT` (responde en `127.0.0.1`, no en la IP del equipo; sin `server.address` falla) y `ProcessorAddressOpenIT` (con `0.0.0.0`, sí); manual con `curl` | ✅ |
| CA2 | `DeadLetterReplaySkipIT` (`{"replayed":1,"skipped":1}`, el JSON roto no rebota; sin clasificación → `{"replayed":2,"skipped":0}` y falla); `ContentFailureTest`; manual en compose: `{"replayed":0,"skipped":2}` y después `0/0` | ✅ |
| CA3 | `DeadLetterReplaySkipIT` (`?all=true` → `{"replayed":2,"skipped":0}`; el roto vuelve al DLT; la zona no cuenta dos veces) | ✅ |
| CA4 | `ZoneRunnerTest` (503, 503, 202 → aceptado, mismo evento, esperas de `Retry-After`; 503 > 60 s → fallido; orden por zona); sin reintentos fallan los 3 | ✅ |
| CA5 | Demo (2026-10-22): 100 ev/s 60 s con PostgreSQL en pausa 30 s → 6 000 × 202, 26 reintentos por 503 (4 eventos), las 4 zonas cuadran, DLT sin mensajes nuevos, dos `WARN` en el `processor` | ✅ |
| CA6 | `git clone` local limpio + `docker compose up -d` + `./mvnw verify` por módulos (2026-10-22): 189 tests en verde | ✅ |

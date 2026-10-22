# Tareas 005 — Remates de fiabilidad

- **Estado:** Aprobado (2026-10-22)
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

- [ ] **T02 — El reproceso se salta los fallos de contenido** (plan §2; CA2, CA3)
  - `ContentFailure` y su test unitario; `DeadLetterReplayer.replay(all)`;
    `?all=true` en el controlador; respuesta `{"replayed", "skipped"}`.
  - `DeadLetterReplayIT`: CA2 y CA3; los casos existentes se adaptan a la respuesta nueva.
  - **Mutación:** `isContentFailure` siempre `false` → CA2 falla; restaurar.
  - **Verificación:** `verify`; manual: `curl -X POST '127.0.0.1:8081/admin/dead-letters/replay'`
    con los mensajes rotos que ya hay en el DLT de compose → `skipped` > 0.

- [ ] **T03 — El simulador reintenta los 503** (plan §3; CA4)
  - `ApiClient` (`Retry-After`), `ZoneRunner` (reintento con reloj y pausa inyectados),
    `ZoneResult` y `SimulationReport` (línea de reintentos).
  - `ZoneRunnerTest`: los tres casos del plan.
  - **Mutación:** sin reintentos → falla; restaurar.
  - **Verificación:** `./mvnw -pl simulator verify`.

- [ ] **T04 — Demo, documentación y cierre** (CA5, CA6)
  - Demo del plan §4.
  - README; nota en el ADR 0005.
  - CA6: clon limpio + `docker compose up -d` + `verify` por módulos.
  - Tabla de verificación de los CA; spec y tareas terminadas; `MEMORY.md` (fase 3
    cerrada, problemas abiertos de la spec 004 resueltos).

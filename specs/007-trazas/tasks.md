# Tareas 007 — Trazas

- **Estado:** Aprobado (2026-10-25); en curso
- **Spec:** [`spec.md`](spec.md) · **Plan:** [`plan.md`](plan.md)

Una tarea cada vez. Está terminada cuando su verificación pasa, `./mvnw verify` está en
verde (por módulos: `-pl shared,api` y `-pl shared,processor,simulator`) y `MEMORY.md` está
actualizado. Al terminar se marca `[x]`.

---

- [x] **T01 — Dependencia, configuración y Jaeger** (plan §1, §2, §7; CA8, CA9)
  - `spring-boot-starter-opentelemetry` en `api` y `processor`; `opentelemetry-sdk-testing`
    en test. Comprobar en `spring-configuration-metadata.json` los nombres de las propiedades
    del plan §1 (endpoint OTLP, muestreo, intervalo del `BatchSpanProcessor`) y anotar los reales.
  - `application.yml` de los dos: muestreo, endpoint OTLP en `127.0.0.1`, `tasks.scheduled`
    desactivado; `spring.kafka.template.observation-enabled` (`api`) y
    `spring.kafka.listener.observation-enabled` (`processor`).
  - `ObservationConfig` con el predicado de actuator en los dos servicios.
  - Jaeger 2.x en `compose.yaml` (versión exacta del día), UI y OTLP solo en `127.0.0.1`.
  - Comprobar si la integración con Docker Compose de Spring Boot detecta Jaeger y qué hace.
  - Los ITs existentes siguen en verde sin cambios (sin `@AutoConfigureTracing` no exportan).
  - **Verificación:** `verify`; manual: `docker compose up -d`, `api` y `processor` en marcha,
    un `POST` con `curl` → en `http://localhost:16686` aparece una traza con tramos de los dos
    servicios; `curl localhost:8080/actuator/health` no crea ninguna.
  - Hecho así (2026-10-25): las propiedades del plan §1 existen con esos nombres
    (`management.opentelemetry.tracing.export.otlp.endpoint`, `…export.schedule-delay`,
    `management.tracing.sampling.probability`). El test va en `spring-boot-starter-opentelemetry-test`
    (`@AutoConfigureTracing`). Spring Boot solo detecta en Compose `otel/opentelemetry-collector-contrib`
    y `grafana/otel-lgtm`, no Jaeger: manda el endpoint explícito. Jaeger `2.22.0`; su API de
    consulta es `/api/v3/...` (la v1 `/api/traces` da 404). El `processor` también activa la
    observation de su `KafkaTemplate` (DLT y reproceso, T04). Manual: un `POST` → una traza
    `http post /api/v1/events` → `crowdpulse.sensor-events send` (api) →
    `crowdpulse.sensor-events process` (processor), hija de la anterior; tras varias llamadas a
    `/actuator/*` en el 8080 y el 8082 y más de 5 s, ninguna traza de actuator ni de `@Scheduled`.
    Los logs ECS ya llevan `traceId`/`spanId`. `verify`: 44 + 150 tests en verde.

- [ ] **T02 — `TracingIT` de la `api`** (plan §3, §8; CA1 lado `api`, CA6, CA8)
  - `TraceAttributes` (paquete `event`) y su `TraceAttributesTest` (unitario, `TestObservationRegistry`).
  - `EventController.accept` pone `crowdpulse.event.id`, `crowdpulse.zone.id` y
    `crowdpulse.event.type` como alta cardinalidad.
  - `TracingIT` (`@AutoConfigureTracing`, `InMemorySpanExporter`, OTLP en un puerto cerrado):
    `traceparent` del mensaje = `traceId` del tramo HTTP; atributos; 422 con atributos y 400
    sin ellos; actuator sin tramos; 202 sin esperar al exportador.
  - **Mutaciones:** `template.observation-enabled: false` → falla la propagación; sin el
    predicado de actuator → falla CA8; restaurar.
  - **Verificación:** `verify -pl shared,api`.

- [ ] **T03 — Atributos y `outcome` en el `processor`** (plan §3, §8; CA1 lado `processor`, CA2–CA4, CA6)
  - `TraceAttributes` del `processor`; `SensorEventListener` pone los atributos tras leer el
    JSON y `crowdpulse.outcome` cuando `process` vuelve.
  - `TracingIT` del `processor`: continúa una `traceparent` conocida; `applied`, `duplicate`,
    `exit_at_zero`; mensaje sin `traceparent` → traza nueva; fallo en el primer intento → dos
    tramos en la misma traza (error sin `outcome`, luego `applied`); OTLP caído → todo
    aplicado y DLT vacío.
  - **Mutaciones:** `listener.observation-enabled: false` → falla CA1; `outcome` antes de
    `process` → falla CA4; restaurar.
  - **Verificación:** `verify -pl shared,processor,simulator`.

- [ ] **T04 — DLT y reproceso** (plan §4, §5; CA5, regla 7)
  - Comprobar si la observation del listener sigue abierta cuando se llama al recoverer. Si sí:
    `outcome=dead_lettered` y `dead_letter.reason` en ella. Si no: observation propia
    `crowdpulse.dead_letter` con el contexto de la `traceparent` del mensaje. Anotar cuál.
  - `DeadLetterReplayer.republish` deja fuera `traceparent`, `tracestate` y `baggage`.
  - `TracingIT` del `processor`: zona inexistente → `dead_lettered`, `reason=other` y la
    publicación al DLT en la misma traza; reproceso → la traza es la de la petición, no la vieja.
  - Los tests de reproceso de la spec 005 siguen en verde.
  - **Mutación:** copiar `traceparent` en `republish` → debería fallar el test del reproceso;
    si no falla, anotar por qué (plan §8) y restaurar.
  - **Verificación:** `verify -pl shared,processor,simulator`.

- [ ] **T05 — Logs con `traceId`** (plan §6; CA7)
  - `TracingIT` de los dos (`OutputCaptureExtension`): la línea del rechazo por zona
    desconocida (`api`) y la del duplicado (`processor`) llevan el `traceId` de su tramo.
  - Comprobar si el `WARN` de `DatabaseOutage` lleva `traceId` y anotarlo (no se exige).
  - **Mutación:** escribir el log del duplicado desde otro hilo sin contexto (o comprobar
    contra un `traceId` distinto) → falla; restaurar.
  - **Verificación:** `verify` por módulos.

- [ ] **T06 — Demo, documentación y cierre** (plan §9; CA9–CA11)
  - Demo del plan §9 (simulador 100 ev/s, 60 s, `--duplicates=5`, PostgreSQL en pausa 30 s;
    luego Jaeger en pausa), con capturas y tiempos anotados aquí.
  - ADR 0007; README («Observabilidad»: Jaeger, búsqueda por `eventId`, muestreo);
    `CLAUDE.md` (Jaeger en `compose.yaml`); `MEMORY.md`.
  - **Verificación:** `git clone` local limpio y `./mvnw verify` por módulos.

## Verificación de los criterios de aceptación

| CA | Cómo se verifica | Resultado |
|----|------------------|-----------|
| CA1 | `TracingIT` `api` (cabecera con su `traceId`) + `TracingIT` `processor` (continúa la cabecera); demo en Jaeger | |
| CA2 | `TracingIT` `processor`: los tres `outcome` | |
| CA3 | `TracingIT` `processor`: mensaje sin `traceparent` | |
| CA4 | `TracingIT` `processor`: fallo en el primer intento; demo con PostgreSQL en pausa | |
| CA5 | `TracingIT` `processor`: zona inexistente → DLT en la misma traza | |
| CA6 | `TracingIT` de los dos con OTLP en un puerto cerrado; demo con Jaeger en pausa | |
| CA7 | `TracingIT` de los dos con `OutputCaptureExtension` | |
| CA8 | `TracingIT` `api`; manual en el `processor` (8082) | |
| CA9 | Manual: `docker compose up -d` y búsqueda de un `eventId` de los logs | |
| CA10 | Demo (T06) | |
| CA11 | Clon limpio + `verify` por módulos | |

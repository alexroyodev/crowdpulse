# Tareas 007 — Trazas

- **Estado:** Terminado (2026-10-25)
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

- [x] **T02 — `TracingIT` de la `api`** (plan §3, §8; CA1 lado `api`, CA6, CA8)
  - `TraceAttributes` (paquete `event`) y su `TraceAttributesTest` (unitario, `TestObservationRegistry`).
  - `EventController.accept` pone `crowdpulse.event.id`, `crowdpulse.zone.id` y
    `crowdpulse.event.type` como alta cardinalidad.
  - `TracingIT` (`@AutoConfigureTracing`, `InMemorySpanExporter`, OTLP en un puerto cerrado):
    `traceparent` del mensaje = `traceId` del tramo HTTP; atributos; 422 con atributos y 400
    sin ellos; actuator sin tramos; 202 sin esperar al exportador.
  - **Mutaciones:** `template.observation-enabled: false` → falla la propagación; sin el
    predicado de actuator → falla CA8; restaurar.
  - **Verificación:** `verify -pl shared,api`.
  - Hecho así (2026-10-25): el backend «colgado» del CA6 es un `ServerSocket` que nunca acepta (el
    sistema completa la conexión y nadie lee): los envíos OTLP fallan por timeout, no por conexión
    rechazada, como con Jaeger en pausa. Timeout OTLP de 2 s en el test; cada `POST` tarda < 1 s.
    Mutaciones → `template.observation-enabled: false` falla «traceparent header of the message»;
    predicado que lo deja pasar todo → falla `actuatorRequestsAreNotTraced`. `verify`: 49 tests.

- [x] **T03 — Atributos y `outcome` en el `processor`** (plan §3, §8; CA1 lado `processor`, CA2–CA4, CA6)
  - `TraceAttributes` del `processor`; `SensorEventListener` pone los atributos tras leer el
    JSON y `crowdpulse.outcome` cuando `process` vuelve.
  - `TracingIT` del `processor`: continúa una `traceparent` conocida; `applied`, `duplicate`,
    `exit_at_zero`; mensaje sin `traceparent` → traza nueva; fallo en el primer intento → dos
    tramos en la misma traza (error sin `outcome`, luego `applied`); OTLP caído → todo
    aplicado y DLT vacío.
  - **Mutaciones:** `listener.observation-enabled: false` → falla CA1; `outcome` antes de
    `process` → falla CA4; restaurar.
  - **Verificación:** `verify -pl shared,processor,simulator`.
  - Hecho así (2026-10-25): un `TraceAttributesTest` también en el `processor`. Mutaciones →
    `listener.observation-enabled: false`: fallan 6 de 7 (no hay tramo de entrega); `outcome`
    antes de `process` → falla CA4 en `hasSize(2)` (el intento revertido ya lleva `applied` y se
    toma por el bueno). **Error mío al deshacer las mutaciones:** `git checkout <fichero>` sobre
    ficheros sin commitear devolvió `SensorEventListener` y `DeadLetterReplayer` a su versión del
    último commit; se rehicieron y, desde entonces, commit antes de cada mutación.

- [x] **T04 — DLT y reproceso** (plan §4, §5; CA5, regla 7)
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
  - Hecho así (2026-10-25): **la observation de la entrega ya está cerrada** cuando el gestor de
    errores llama al recoverer (su contexto sigue activo: la publicación al DLT colgaba de ella,
    pero sin los atributos). Se usó la alternativa del plan: `TraceAttributes.deadLettered` abre la
    observation `crowdpulse.dead.letter` (con `outcome` y `reason`) alrededor de la publicación,
    sin leer la cabecera: el contexto ya es el bueno. Como toda observation, es también un timer
    (`crowdpulse_dead_letter_seconds`). **Reproceso:** el filtro de cabeceras de la sección 5 se
    probó y se quitó: el `KafkaTemplate` **sustituye** la `traceparent` copiada por la suya (con el
    filtro quitado, el mensaje republicado sigue teniendo una sola), así que no hacía nada
    (principio 1). El test comprueba la traza del reproceso y que hay una sola `traceparent`.

- [x] **T05 — Logs con `traceId`** (plan §6; CA7)
  - `TracingIT` de los dos (`OutputCaptureExtension`): la línea del rechazo por zona
    desconocida (`api`) y la del duplicado (`processor`) llevan el `traceId` de su tramo.
  - Comprobar si el `WARN` de `DatabaseOutage` lleva `traceId` y anotarlo (no se exige).
  - **Mutación:** escribir el log del duplicado desde otro hilo sin contexto (o comprobar
    contra un `traceId` distinto) → falla; restaurar.
  - **Verificación:** `verify` por módulos.
  - Hecho así (2026-10-25): se comprueba el `WARN` de una zona desconocida (`api`) y el del `EXIT`
    con la zona vacía (`processor`); el duplicado se registra en `DEBUG` y no sale en la salida.
    Mutación → `MDC.remove("traceId")` antes del `WARN` → falla «traceId in …». El `WARN` de
    `DatabaseOutage` lleva el `traceId` de la entrega que falla (y el de vuelta, el de la que
    funciona): desde ese log se llega a la traza del evento que esperó. `verify`: 50 + 161 tests.

- [x] **T06 — Demo, documentación y cierre** (plan §9; CA9–CA11)
  - Demo del plan §9 (simulador 100 ev/s, 60 s, `--duplicates=5`, PostgreSQL en pausa 30 s;
    luego Jaeger en pausa), con capturas y tiempos anotados aquí.
  - ADR 0007; README («Observabilidad»: Jaeger, búsqueda por `eventId`, muestreo);
    `CLAUDE.md` (Jaeger en `compose.yaml`); `MEMORY.md`.
  - **Verificación:** `git clone` local limpio y `./mvnw verify` por módulos.
  - Hecho así (2026-10-25), demo con el simulador (100 ev/s, 60 s, `--duplicates=5`) y
    PostgreSQL en pausa de 11:46:52 a 11:47:23 UTC: 6000 × 202, 26 reintentos por 503, la API
    cuadra 17,3 s después del último envío. En Jaeger, 178 tramos `duplicate` y 4 intentos con
    error en el intervalo revisado. Traza `004a231d…` ([captura](demo-espera-bd.png)): aceptado en
    314 ms; intentos con error a +18,6 s (5,1 s) y +30,9 s (5,0 s); aplicado a +45,1 s, 45,3 s en
    total. El duplicado `1401d492…`: búsqueda por `crowdpulse.event.id` → 2 trazas, la del envío y
    la del duplicado, que se procesó 41,8 s después porque su partición esperaba a la BD
    ([captura](demo-busqueda-duplicado.png)). Luego `docker compose pause jaeger` y otro simulador
    a 100 ev/s, 30 s: 3000 × 202, sin reintentos, la API cuadra en 0,5 s, nada en el DLT; los
    envíos de tramos fallan por timeout. Consola del navegador sin errores. La API de consulta
    de Jaeger 2 no filtra por atributo con `query.attributes[...]` (devuelve todo); la búsqueda
    se hizo en la interfaz.

## Verificación de los criterios de aceptación

| CA | Cómo se verifica | Resultado |
|----|------------------|-----------|
| CA1 | `TracingIT` `api` (cabecera con su `traceId`; sin observation en la plantilla falla) + `TracingIT` `processor` (continúa la cabecera; sin observation en el listener falla); demo: una traza `api` → `processor` en Jaeger | ✅
| CA2 | `TracingIT` `processor`: `exit_at_zero`, `applied`, `duplicate` | ✅
| CA3 | `TracingIT` `processor`: sin `traceparent` → traza nueva (padre inválido) con el `eventId` | ✅
| CA4 | `TracingIT` `processor`: dos tramos en la misma traza, el revertido con error y sin `outcome` (`outcome` antes de `process` → falla); demo: dos intentos con error y luego `applied` | ✅
| CA5 | `TracingIT` `processor`: entrega con error → `crowdpulse.dead.letter` (`dead_lettered`, `other`) → envío al DLT, en la traza de la cabecera | ✅
| CA6 | `TracingIT` de los dos con un backend OTLP que nunca responde (cada `POST` < 1 s; 20 eventos aplicados, DLT vacío); demo con Jaeger en pausa | ✅
| CA7 | `TracingIT` de los dos con `OutputCaptureExtension` (`MDC.remove` → falla) | ✅
| CA8 | `TracingIT` `api` (sin el predicado falla); manual en el 8080 y el 8082 (T01) | ✅
| CA9 | Manual: `docker compose up -d`; traza abierta desde el `traceId` de un log y búsqueda por `crowdpulse.event.id` en la interfaz | ✅
| CA10 | Demo (T06) | ✅
| CA11 | `git clone` local limpio + `./mvnw verify -pl shared,api` y `-pl shared,processor,simulator` (2026-10-25): 211 tests en verde (194 de antes + 17 nuevos) | ✅ |

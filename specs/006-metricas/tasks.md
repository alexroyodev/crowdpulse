# Tareas 006 — Métricas

- **Estado:** Aprobado (2026-10-25); en curso
- **Spec:** [`spec.md`](spec.md) · **Plan:** [`plan.md`](plan.md)

Una tarea cada vez. Está terminada cuando su verificación pasa, `./mvnw verify` está en
verde (por módulos: `-pl shared,api` y `-pl shared,processor,simulator`) y `MEMORY.md` está
actualizado. Al terminar se marca `[x]`.

---

- [x] **T01 — Puerto de gestión del `processor`** (plan §1; CA1)
  - `micrometer-registry-prometheus` en `api` y `processor`.
  - `processor`: `management.server.port: 8082`,
    `address: ${CROWDPULSE_MANAGEMENT_ADDRESS:0.0.0.0}` y exponer `health, prometheus`.
    `api`: exponer `health, prometheus`.
  - `DatabaseOutageIT`: health por el puerto de gestión (`@LocalManagementPort`).
  - `ProcessorAddressIT`: el puerto de gestión responde en la IP del equipo; el principal,
    no (ya estaba); el reproceso en el puerto de gestión da 404.
  - ADR 0006; `CLAUDE.md` y README: health del `processor` en el 8082.
  - **Mutación:** sin `management.server.port` → falla el test del puerto de gestión; restaurar.
  - **Verificación:** `verify`; manual: `curl 127.0.0.1:8082/actuator/health`,
    `curl <IP del equipo>:8082/actuator/prometheus` responde y `<IP del equipo>:8081` no.
  - Hecho así (2026-10-25): la mutación «sin `management.server.port`» rompe el contexto (no
    hay puerto de gestión que inyectar), no la aserción; se usó en su lugar
    `CROWDPULSE_MANAGEMENT_ADDRESS:127.0.0.1` → falla «reachable on 192.168.32.1», el motivo
    correcto. Con `RANDOM_PORT`, Spring Boot también sortea el puerto de gestión. Manual: health
    `UP` en el 8082, `/actuator/prometheus` responde en la IP del equipo y el 8081 no. Para
    `spring-boot:run` con `-pl` hace falta el pom padre instalado (`./mvnw -N install`).

- [x] **T02 — Contadores de eventos procesados** (plan §2; CA2, CA3)
  - `Processed` (record con `Outcome`); `EventProcessor.process` lo devuelve;
    `SensorEventListener` llama a `EventMetrics.record` cuando `process` vuelve.
  - `EventMetrics`: `crowdpulse.events.processed`, los dos timers (histograma, 1 ms–60 s,
    retraso negativo → 0) y `crowdpulse.alerts`.
  - `EventMetricsTest` (unitario) y `ProcessingMetricsIT` (CA2, CA3; diferencias antes/después).
  - Los tests que ya llaman a `process` se adaptan al nuevo tipo de retorno, sin cambiar lo
    que comprueban.
  - **Mutaciones:** `DUPLICATE` contado como `APPLIED` → CA2 falla; contar dentro de
    `process` antes de `alerts.insert` → CA3 falla; restaurar.
  - **Verificación:** `verify`.
  - Hecho así (2026-10-25): mutaciones → CA2 «expected 10 but was 14»; CA3 «expected 1 but
    was 2». La latencia también se pone a 0 si saliera negativa (relojes de `api` y `processor`).
    El `verify` destapó un fallo de T01: `TwoInstancesIT` arranca dos `processor` a mano y los
    dos abrían el 8082; corregido en un commit aparte (`--management.server.port=0`, también en
    `ProcessorRestartIT`).

- [x] **T03 — Contador del DLT** (plan §2)
  - `crowdpulse.events.dead.lettered` con `reason` (`ContentFailure.TYPES` sobre la causa más
    interna), en el `loggingRecoverer` de `KafkaConfig`.
  - `DeadLetterIT` ampliado: JSON roto → `content` +1; zona inexistente → `other` +1.
  - **Mutación:** `reason` siempre `other` → falla; restaurar.
  - **Verificación:** `verify`.
  - Hecho así (2026-10-25): el `reason` usa la causa directa de la excepción del listener (la
    clase que Spring Kafka escribe en `kafka_dlt-exception-cause-fqcn`) y
    `ContentFailure.isContentFailure`, la misma función que el reproceso. Mutación → «expected 1
    but was 0».

- [ ] **T04 — Gauges de zona leídos de la BD** (plan §3; CA4)
  - `MetricsProperties` (`crowdpulse.metrics.zone-refresh: 5s`), `ZoneMetrics` con
    `refresh()` (tres `MultiGauge`), `@Scheduled` y primer refresco al arrancar;
    `@EnableScheduling`.
  - Con la BD caída se quedan los últimos valores (`DEBUG`).
  - `ZoneMetricsIT`: el gauge coincide con `zone_occupancy` tras procesar; ocupación puesta
    con SQL → el gauge la refleja sin procesar ningún evento.
  - **Mutación:** gauge alimentado en memoria desde `EventProcessor` → falla el segundo
    test; restaurar.
  - **Verificación:** `verify`.

- [ ] **T05 — BD caída: gauge del `processor` y 503 en la `api`** (plan §4; CA5)
  - `DatabaseOutage` registra `crowdpulse.database.outage` (1/0).
  - `DatabaseOutageIT` ampliado: 1 con PostgreSQL en pausa, 0 al volver.
  - `DatabaseDownIT` (api) ampliado: `http.server.requests` con `uri=/api/v1/events` y
    `status=503` sube.
  - **Mutación:** gauge siempre 0 → falla; restaurar.
  - **Verificación:** `verify` de los dos módulos.

- [ ] **T06 — `/actuator/prometheus` y métricas de Kafka** (plan §5, §6; CA1)
  - `PrometheusEndpointIT` en `api` y `processor` (`@AutoConfigureMetrics`): están los nombres
    del contrato de cada servicio.
  - Comprobar que el retraso del consumidor (`kafka_consumer_fetch_manager_records_lag_max`
    o similar) aparece sin configurar nada y anotar el nombre exacto. **Si no aparece, parar y
    preguntar.**
  - **Verificación:** `verify`; manual: `curl` a los dos `/actuator/prometheus` con las apps
    en marcha.

- [ ] **T07 — Prometheus y Grafana en compose, con el panel** (plan §7; CA6)
  - Versiones exactas (última estable en Docker Hub ese día) de `prom/prometheus` y
    `grafana/grafana`.
  - `compose.yaml`; `observability/prometheus.yml` (8080 y 8082 en
    `host.docker.internal`, cada 5 s); provisioning de Grafana (datasource y panel).
  - Panel de seis filas, una por pregunta de la spec, montado en Grafana y exportado a
    `observability/grafana/dashboards/crowdpulse.json`.
  - Comprobar si la integración de Docker Compose de Spring Boot levanta también estos
    servicios al arrancar las apps, y decidir (anotarlo aquí).
  - **Verificación:** `docker compose down -v && docker compose up -d` → en
    `localhost:9090/targets` los dos destinos `up`; en `localhost:3000` el panel cargado, sin
    pasos a mano.

- [ ] **T08 — Demo, documentación y cierre** (CA7, CA8)
  - Demo: simulador a 100 ev/s durante 60 s, `docker compose pause postgres` 30 s a mitad →
    capturas del panel (caída, retraso de consumo, `database_outage`, recuperación);
    ocupación del panel = `GET /zones`.
  - README: sección «Observabilidad» (URLs, panel, consultas PromQL explicadas).
  - Verificación desde un clon limpio, por módulos.
  - Spec, plan y tareas a «Terminado»; `MEMORY.md` actualizado.
  - **Verificación:** CA7 y CA8.

## Verificación de los criterios de aceptación
(Se rellena al terminar.)

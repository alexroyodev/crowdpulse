# Tareas 006 — Métricas

- **Estado:** Terminado (2026-10-25)
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

- [x] **T04 — Gauges de zona leídos de la BD** (plan §3; CA4)
  - `MetricsProperties` (`crowdpulse.metrics.zone-refresh: 5s`), `ZoneMetrics` con
    `refresh()` (tres `MultiGauge`), `@Scheduled` y primer refresco al arrancar;
    `@EnableScheduling`.
  - Con la BD caída se quedan los últimos valores (`DEBUG`).
  - `ZoneMetricsIT`: el gauge coincide con `zone_occupancy` tras procesar; ocupación puesta
    con SQL → el gauge la refleja sin procesar ningún evento.
  - **Mutación:** gauge alimentado en memoria desde `EventProcessor` → falla el segundo
    test; restaurar.
  - **Verificación:** `verify`.
  - Hecho así (2026-10-25): sin record `MetricsProperties`; el periodo va en
    `@Scheduled(fixedDelayString = "${crowdpulse.metrics.zone-refresh}")`, porque la anotación es
    lo único que lo lee y con un record haría falta SpEL (acepta `5s`). `@EnableScheduling` en
    `ProcessorApplication`. Lectura con `OccupancyRepository.findAll()` (sin bloqueo). Mutación
    (ocupación de un mapa en memoria) → «expected 13000 but was 0». `verify` del `processor`:
    49 IT + 30 unitarios en verde.

- [x] **T05 — BD caída: gauge del `processor` y 503 en la `api`** (plan §4; CA5)
  - `DatabaseOutage` registra `crowdpulse.database.outage` (1/0).
  - `DatabaseOutageIT` ampliado: 1 con PostgreSQL en pausa, 0 al volver.
  - `DatabaseDownIT` (api) ampliado: `http.server.requests` con `uri=/api/v1/events` y
    `status=503` sube.
  - **Mutación:** gauge siempre 0 → falla; restaurar.
  - **Verificación:** `verify` de los dos módulos.
  - Hecho así (2026-10-25): el gauge se lee con `isDown()` (sincronizado, como `failed`/`succeeded`).
    Mutación → «expected 1 but was 0». En la `api` no hay mecanismo propio que mutar (la métrica es
    de Spring Boot); el test exige que suba exactamente 1, así que falla si la métrica no está.

- [x] **T06 — `/actuator/prometheus` y métricas de Kafka** (plan §5, §6; CA1)
  - `PrometheusEndpointIT` en `api` y `processor` (`@AutoConfigureMetrics`): están los nombres
    del contrato de cada servicio.
  - Comprobar que el retraso del consumidor (`kafka_consumer_fetch_manager_records_lag_max`
    o similar) aparece sin configurar nada y anotar el nombre exacto. **Si no aparece, parar y
    preguntar.**
  - **Verificación:** `verify`; manual: `curl` a los dos `/actuator/prometheus` con las apps
    en marcha.
  - Hecho así (2026-10-25): el retraso del consumidor aparece solo, como
    `kafka_consumer_fetch_manager_records_lag_max` (comprobado a mano en T01 y en el test); `NaN`
    mientras no hay nada que consumir. `@AutoConfigureMetrics` está en
    `spring-boot-micrometer-metrics-test`: el `processor` añade `spring-boot-starter-actuator-test`
    (solo tests), igual que ya tenía la `api`. El test del `processor` publica un evento que cambia
    el nivel de alerta y un mensaje roto, porque los contadores con etiquetas solo aparecen al usarse.
    `verify`: `api` 44 IT, `processor` 50 IT + 30 unitarios, en verde.

- [x] **T07 — Prometheus y Grafana en compose, con el panel** (plan §7; CA6)
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
  - Hecho así (2026-10-25): `prom/prometheus:v3.15.1` y `grafana/grafana:13.2.4` (últimas
    estables en Docker Hub ese día). El panel se escribió directamente en JSON (seis paneles, uno
    por pregunta, ventana `rate(...[30s])`), no exportado de la interfaz: sale más corto y legible.
    Se montan solo `provisioning/datasources` y `provisioning/dashboards`; montar la carpeta entera
    ocultaba las de Grafana (`plugins`, `alerting`) y daba errores en el log. La integración de
    Docker Compose de Spring Boot arranca también estos dos servicios con `spring-boot:run`; no
    son servicios que reconozca, así que no les crea conexión: se deja así. Verificado tras
    `down -v` + `up -d`: panel y datasource cargados, sin errores en el log de Grafana; con las
    apps en marcha, los destinos `api` y `processor` en `up` y las consultas devuelven datos.

- [x] **T08 — Demo, documentación y cierre** (CA7, CA8)
  - Demo: simulador a 100 ev/s durante 60 s, `docker compose pause postgres` 30 s a mitad →
    capturas del panel (caída, retraso de consumo, `database_outage`, recuperación);
    ocupación del panel = `GET /zones`.
  - README: sección «Observabilidad» (URLs, panel, consultas PromQL explicadas).
  - Verificación desde un clon limpio, por módulos.
  - Spec, plan y tareas a «Terminado»; `MEMORY.md` actualizado.
  - **Verificación:** CA7 y CA8.
  - Hecho así (2026-10-25):
    - Sin capturas de pantalla: no hay forma de sacarlas desde aquí sin usar tu navegador. En su
      lugar, la demo lanzó cada 5 s contra Prometheus las mismas consultas del panel (tabla de CA7).
    - La primera demo destapó que `records_lag_max` se queda en su pico ~1 min después de vaciarse
      la cola: el panel 4 muestra también el retraso actual por partición (`records_lag`).
    - Con la BD caída **la API tampoco acepta** (valida la zona contra la BD): la cola se forma en
      los sensores, no en Kafka, y el retraso de consumo solo sube en la ráfaga de vuelta (máx.
      280–340 en la primera demo; 48 de retraso actual en la segunda). Explicado en el README.
    - Aviso de seguridad automático tras T07: Prometheus y Grafana no tienen autenticación →
      publicados solo en `127.0.0.1` (comprobado: desde la IP del equipo no hay conexión).
    - La verificación desde un clon limpio pilló que `PrometheusEndpointIT` exigía `records_lag`,
      que aparece en Micrometer más tarde de lo que espera el test (lo añadí sin volver a
      ejecutarlo). Se quitó del test, que conserva `records_lag_max`; corregido en su commit.

## Verificación de los criterios de aceptación

| CA | Cómo se verifica | Resultado |
|----|------------------|-----------|
| CA1 | `PrometheusEndpointIT` en `api` (métrica HTTP de `POST /api/v1/events`) y en `processor` (todas las del contrato y `records_lag_max`); `ProcessorAddressIT` (8082 abierto, 8081 no, reproceso 404 en el 8082; con el servidor de gestión en `127.0.0.1` falla); manual con `curl` | ✅ |
| CA2 | `ProcessingMetricsIT`: 10 distintos + 4 copias → `applied`+`exit_at_zero` = 11 (con el marcador), `duplicate` = 4; contando duplicados como aplicados falla (14 ≠ 10) | ✅ |
| CA3 | `ProcessingMetricsIT`: `alerts.insert` falla una vez → reintento → cuenta 1; contando dentro de la transacción falla (2 ≠ 1) | ✅ |
| CA4 | `ZoneMetricsIT` (gauge = `zone_occupancy`; ocupación puesta con SQL → el gauge la refleja sin eventos; desde memoria falla); demo: ocupación del panel = `GET /zones` en las 5 zonas | ✅ |
| CA5 | `DatabaseOutageIT` (gauge 1 en la pausa, 0 al volver; siempre 0 → falla); `DatabaseDownIT` (`http.server.requests` `status=503` +1) | ✅ |
| CA6 | `docker compose down -v && up -d`: datasource y panel cargados sin pasos a mano, sin errores en el log de Grafana; destinos `api` y `processor` en `up` | ✅ |
| CA7 | Demo (2026-10-25), ver abajo: aplicados a 0 y `database_outage` = 1 en la pausa, 503 en la API, ráfaga de vuelta con p95 de varios segundos, recuperación; simulador OK (6 000 × 202), DLT vacío; ocupación del panel = API | ✅ (el retraso en Kafka sube poco; motivo en T08) |
| CA8 | `git clone` local limpio + `./mvnw verify -pl shared,api` y `-pl shared,processor,simulator` (2026-10-25): 202 tests en verde (189 de antes + 13 nuevos) | ✅ |

Demo de CA7 (primera ejecución; muestras cada 5 s de las consultas del panel, PostgreSQL en
pausa de 11:03:32 a 11:04:03):

| Hora | API (ev/s) | `processor` aplicados (ev/s) | p95 latencia | retraso Kafka (máx.) | BD caída |
|------|-----------|------------------|-----|-----|----|
| 11:03:40 | 202 = 65,5 | 64,5 | 0,3 s | 0 | 0 |
| 11:03:57 | 202 = 21,1; 503 = 0,6 | 1,8 | — | 0 | **1** |
| 11:04:02 | 202 = 0; 503 = 0,9 | 0 | — | 0 | **1** |
| 11:04:14 | 202 = 96,6 | 40,8 | 5,6 s | 280 | 0 |
| 11:04:31 | 202 = 160,3 | 143,0 | 9,8 s | 340 | 0 |
| 11:04:53 | 0 | 8,8 | 11,3 s | 340 | 0 |
| 11:05:27 | 0 | 0 | — | sin datos | 0 |

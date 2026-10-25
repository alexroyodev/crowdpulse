# CrowdPulse

Estimación en tiempo real de la ocupación por zonas de un estadio a partir de eventos de
sensores de entrada y salida. Proyecto de aprendizaje y portfolio: lo importante es
poder demostrar cómo se comporta ante duplicados, desorden, concurrencia y reinicios.

**Estado:** fase 4 (observabilidad y presentación) terminada — specs [001 (ingesta)](specs/001-ingesta-mvp/spec.md),
[002 (alertas)](specs/002-alertas-umbrales/spec.md), [003 (Kafka)](specs/003-procesamiento-asincrono/spec.md),
[004 (caídas)](specs/004-fiabilidad-ante-caidas/spec.md), [005 (remates)](specs/005-remates-de-fiabilidad/spec.md),
[006 (métricas)](specs/006-metricas/spec.md), [007 (trazas)](specs/007-trazas/spec.md) y
[008 (dashboard)](specs/008-dashboard/spec.md) terminadas.

```
simulator ──POST /api/v1/events──► api ──clave zoneId──► Kafka ──► processor ──► PostgreSQL
                                    │     crowdpulse.sensor-events        │
                                    │                                     └──► …-events.dlt
                                    └──◄── GET /api/v1/zones, /alerts ◄── PostgreSQL
```

- **`api/`** — Spring Boot 4 (Java 21). Valida cada evento, comprueba que la zona existe,
  lo publica en Kafka y responde `202` solo cuando Kafka lo ha guardado. Sirve las
  consultas de ocupación y alertas.
- **`processor/`** — Spring Boot 4. Consume los eventos y, en una transacción por evento,
  actualiza la ocupación de su zona y su nivel de alerta. Confirma el offset después de
  guardar.
- **`shared/`** — lo que las dos aplicaciones ven igual: migraciones Flyway, enums y el
  mensaje `SensorEventMessage` (versión 1).
- **`simulator/`** — Java sin dependencias. Genera tráfico de llegada y salida de público
  y comprueba al final que la API cuadra.
- **`dashboard/`** — React + TypeScript (Vite). La página para quien opera el estadio:
  ocupación y nivel de cada zona en vivo y los últimos cambios de nivel (ver «Dashboard»).
- **PostgreSQL 18** y **Apache Kafka 4.2** (KRaft); en local, con Docker Compose.
- **Prometheus** y **Grafana**: métricas de la API y del `processor` y un panel; **Jaeger**:
  la traza de cada evento de punta a punta (ver «Observabilidad»).

## Garantías (y el test que las demuestra)

| Garantía | Test |
|----------|------|
| Un `eventId` repetido se acepta (202) pero no cuenta dos veces | `EventProcessingIT`, `ConcurrentIngestionIT`, `KafkaProcessingIT` |
| Evento y ocupación se guardan juntos o ninguno (una transacción) | `TransactionAtomicityIT` |
| 1 000 eventos concurrentes en una zona → exactamente 1 000 | `ConcurrentIngestionIT`, `KafkaProcessingIT` |
| La ocupación nunca es negativa: un `EXIT` con la zona vacía se queda en 0 y se marca como anomalía | `OccupancyTest`, `EventProcessingIT` |
| `occurredAt` (sensor), `receivedAt` (API) y `processedAt` (`processor`) se guardan por separado | `EventProcessingIT`, `KafkaProcessingIT` |
| Nunca un `202` sin el evento en Kafka: si Kafka no confirma, `503` | `KafkaOutageIT` |
| Un evento rechazado (`400`, `422`) no llega a Kafka | `EventIngestionIT` |
| Los eventos de una zona van a la misma partición y se aplican en orden | `EventPublishingIT`, `KafkaProcessingIT` |
| Si el `processor` cae antes de guardar, el evento vuelve y se aplica; si cae después, vuelve y no cuenta dos veces | `AtLeastOnceIT` |
| Reiniciar el `processor` a mitad de 1 000 eventos no pierde ni duplica ninguno | `ProcessorRestartIT` |
| Un mensaje que no se puede procesar va al DLT y su zona sigue adelante | `DeadLetterIT` |
| Con PostgreSQL caído, el `processor` espera sin límite: al volver, nada perdido, duplicado, desordenado ni en el DLT | `DatabaseOutageIT`, `DatabaseUnavailableTest` |
| Con Kafka caído a mitad del consumo, al volver cada evento se aplica una vez | `KafkaRestartIT` |
| Con dos `processor`, si uno cae el otro termina el trabajo, exacto y en orden | `TwoInstancesIT` |
| El DLT se reprocesa cuando se arregla la causa, una vez por mensaje y sin contar dos veces | `DeadLetterReplayIT` |
| El reproceso se salta los mensajes con contenido roto (no rebotan); `?all=true` lo reprocesa todo | `DeadLetterReplaySkipIT`, `ContentFailureTest` |
| El `processor` solo es accesible desde el propio equipo (salvo que se configure otra cosa) | `ProcessorAddressIT`, `ProcessorAddressOpenIT` |
| Sin base de datos la API responde `503`, nunca `500` | `DatabaseDownIT` |
| El estado y las alertas sobreviven a un reinicio | `ProcessorRestartIT` |
| Errores en formato `ProblemDetail` (RFC 9457), en español y sin trazas | `ApiErrorsIT`, `EventIngestionIT` |
| Alerta al llegar al 85 % (`WARNING`) y al 95 % (`CRITICAL`), con histéresis de 5 puntos: oscilar en el umbral genera un solo cambio | `AlertPolicyTest`, `AlertProcessingIT` |
| Evento, ocupación y cambio de nivel se guardan juntos o ninguno | `AlertAtomicityIT` |
| Eventos concurrentes que cruzan un umbral lo registran una sola vez | `ConcurrentAlertIT` |
| El recorrido de un evento (API → Kafka → `processor`) es una sola traza, con un tramo por intento | `TracingIT` (api y processor) |
| Sin backend de trazas (o colgado), los eventos se aceptan y se procesan igual | `TracingIT` (api y processor) |

Cada test de garantía se ha validado una vez quitando el mecanismo que la da y viéndolo
fallar. Las decisiones técnicas están en [`docs/adr/`](docs/adr/) (la de Kafka, en el
[ADR 0004](docs/adr/0004-kafka-entre-api-y-processor.md); la de las caídas, en el
[ADR 0005](docs/adr/0005-fallo-de-infraestructura-frente-a-mensaje-malo.md); la de las trazas, en el
[ADR 0007](docs/adr/0007-trazas-con-opentelemetry-y-jaeger.md)) y los principios del proyecto en
[`docs/constitution.md`](docs/constitution.md).

## Requisitos
- **JDK 21**, con `JAVA_HOME` apuntando a él (`./mvnw` lo usa).
- **Docker** en marcha (lo necesitan los tests de integración y las aplicaciones en local).
- El puerto **9092** libre: Kafka se publica ahí en local.
- Para el dashboard, **Node 22.12 o posterior** (con `npm`).

## Uso

```bash
./mvnw test                   # tests unitarios (sin Docker)
./mvnw verify                 # todos, incluidos los de integración con Testcontainers

docker compose up -d                  # PostgreSQL, Kafka, Prometheus y Grafana
./mvnw -pl api spring-boot:run        # API en el 8080 (otra terminal)
./mvnw -pl processor spring-boot:run  # processor en 127.0.0.1:8081 (otra terminal)
curl localhost:8080/actuator/health
curl 127.0.0.1:8082/actuator/health  # actuator del processor: su propio puerto (ADR 0006)
```

### Dashboard

```bash
cd dashboard
npm ci          # la primera vez: instala las versiones de package-lock.json
npm run dev     # http://localhost:5173 (con la API en marcha)
npm test        # tests (Vitest + Testing Library, sin navegador ni API)
npm run build   # comprueba los tipos y compila
```

- Enseña lo que dan `GET /api/v1/zones` y `GET /api/v1/alerts`, y les vuelve a preguntar
  2 s después de cada respuesta. No calcula nada: el nivel de cada zona es el que decidió el
  `processor` (los umbrales no están en su código), con su color **y** su nombre.
- El servidor de desarrollo reenvía `/api` a `127.0.0.1:8080`, así que la API no necesita CORS.
- **Si la API no responde** (parada, o `503` con PostgreSQL caído), la página sigue enseñando
  los últimos datos con el aviso «Sin conexión con la API: datos de las hh:mm:ss», y se pone al
  día sola cuando vuelve. Nunca enseña ceros como si fueran reales.

### API

```bash
# Registrar un evento -> 202 cuando Kafka lo tiene (también si es un duplicado)
curl -i -X POST localhost:8080/api/v1/events -H 'Content-Type: application/json' -d '{
  "eventId": "3f2b8c1e-6a4d-4e0f-9b7a-2c5d8e1f0a93",
  "sensorId": "NORTE-P03",
  "zoneId": "NORTE",
  "type": "ENTRY",
  "occurredAt": "2026-10-19T18:30:05.123Z"
}'

curl localhost:8080/api/v1/zones          # todas las zonas, con su alertLevel
curl localhost:8080/api/v1/zones/NORTE    # una zona (404 si no existe)
curl localhost:8080/api/v1/alerts         # cambios de nivel, los más recientes primero
curl 'localhost:8080/api/v1/alerts?zoneId=PALCO&limit=10'
```

| Respuesta | Cuándo |
|-----------|--------|
| `202` | Evento guardado en Kafka, nuevo o duplicado; se procesará enseguida (consistencia eventual) |
| `400` | JSON mal formado, campo ausente o con formato inválido, `occurredAt` más de 5 min en el futuro. El cuerpo incluye `errors: [{field, message}]` |
| `422` | `zoneId` no existe en el catálogo |
| `503` | Kafka no confirma el evento en unos 3 s, o la base de datos no responde (no se puede consultar el catálogo); incluye `Retry-After: 1` y el sensor reintenta |

Zonas: `NORTE`, `SUR` (20 000 plazas), `ESTE`, `OESTE` (15 000) y `PALCO` (200, para la
demo de alertas).

### Alertas

| Nivel | Se entra al llegar a | Se sale al bajar de |
|-------|----------------------|---------------------|
| `WARNING` | 85 % | 80 % |
| `CRITICAL` | 95 % | 90 % (vuelve a `WARNING`) |

Cada cambio de nivel queda en `GET /api/v1/alerts` y en el log (`WARN "Alert level
changed"` del `processor`). Los umbrales están en el `application.yml` del `processor`
(`crowdpulse.alerts`).

### Simulador

```bash
./mvnw -pl simulator package
java -jar simulator/target/simulator.jar --rate=100 --duration=60s --duplicates=5
java -jar simulator/target/simulator.jar --help
```

Envía los eventos de cada zona en orden desde su propio hilo, con más entradas al
principio y más salidas al final. Como un sensor, si la API responde `503` espera lo que
diga `Retry-After` y reenvía el mismo evento (hasta 60 s), y espera hasta 10 s cada
respuesta. Con PostgreSQL en pausa 30 s a mitad de la demo, el informe termina en OK. Al terminar espera a que la API refleje los eventos
(consulta cada 500 ms, hasta 30 s), compara el cambio de ocupación de cada zona con el
esperado (entradas − salidas únicas) y sale con código `0` si todo cuadra y todas las
respuestas fueron `202`.

Demo de alertas: `--zones=PALCO --rate=20 --duration=60s` lleva el palco al 85 % y al 95 %
en unos segundos; después, `curl 'localhost:8080/api/v1/alerts?zoneId=PALCO'`.

> En Git Bash (Windows) añade `-Dstdout.encoding=UTF-8` para ver bien los acentos.

### Latencia y mensajes no procesables

La latencia del sistema es `processed_at − received_at` de cada evento. En la demo de
100 ev/s, con el sistema ya caliente: p50 22 ms, p95 39 ms, p99 58 ms.

```bash
docker compose exec postgres psql -U crowdpulse -d crowdpulse -c "
SELECT count(*),
       percentile_cont(0.5)  WITHIN GROUP (ORDER BY processed_at - received_at) AS p50,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY processed_at - received_at) AS p95,
       percentile_cont(0.99) WITHIN GROUP (ORDER BY processed_at - received_at) AS p99
FROM sensor_event WHERE received_at > now() - interval '5 minutes';"

# Mensajes que el processor no pudo procesar (también en su log como ERROR)
# (Reprocesarlos: ver más abajo)
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092   --topic crowdpulse.sensor-events.dlt --from-beginning --formatter-property print.headers=true
```

### Caídas y reproceso del DLT

Si PostgreSQL deja de responder, el `processor` espera sin límite (reintentos cada 1, 2, 4…
hasta 30 s), registra un `WARN` al caer y otro al volver, y su `/actuator/health` pasa a
`DOWN`; la API responde `503` mientras tanto. Nada va al DLT por una caída.

```bash
docker compose pause postgres     # simular la caída (pause, no stop: el puerto se conserva)
docker compose unpause postgres

# Arreglada la causa, reprocesar lo que haya en el DLT desde la última vez
curl -X POST 127.0.0.1:8081/admin/dead-letters/replay            # {"replayed": n, "skipped": m}
curl -X POST '127.0.0.1:8081/admin/dead-letters/replay?all=true' # todo el DLT, desde el principio
```

- El reproceso normal **se salta** los mensajes cuyo contenido está roto (JSON ilegible,
  campos que faltan, versión desconocida): ningún cambio del entorno los arregla. Nada se
  borra del DLT; `?all=true` vuelve a pasar todo, también lo saltado.
- El endpoint no tiene autenticación, así que el `processor` **solo escucha en
  `127.0.0.1`**. Para abrirlo: `CROWDPULSE_PROCESSOR_ADDRESS=0.0.0.0`.
- Su actuator (`health`, `prometheus`) va aparte, en el **8082**, abierto a todas las
  direcciones para que Prometheus (en Docker) llegue a él; solo es de lectura
  ([ADR 0006](docs/adr/0006-puerto-de-gestion-separado-en-el-processor.md)). La primera vez,
  el firewall de Windows puede preguntar.

### Observabilidad

`docker compose up -d` levanta también **Prometheus** (`http://localhost:9090`) y
**Grafana** (`http://localhost:3000`, panel *CrowdPulse*, sin iniciar sesión), los dos solo
en este equipo. Prometheus lee cada 5 s `/actuator/prometheus` de la API (8080) y del
`processor` (8082) a través de `host.docker.internal`: las aplicaciones corren fuera de
Docker. El panel y la fuente de datos se cargan solos desde `observability/grafana/`.

El panel tiene una gráfica por pregunta ([spec 006](specs/006-metricas/spec.md)):

| Pregunta | Consulta (simplificada) |
|---|---|
| 1. ¿Cuántos eventos acepta o rechaza la API? | `sum by (status) (rate(http_server_requests_seconds_count{uri="/api/v1/events"}[30s]))` — métrica HTTP de Spring Boot |
| 2. ¿Qué hace el `processor` con ellos? | `sum by (outcome) (rate(crowdpulse_events_processed_total[30s]))` (`applied`, `duplicate`, `exit_at_zero`) y `crowdpulse_events_dead_lettered_total` por `reason` |
| 3. ¿Cuánto tarda un evento en contar? | `histogram_quantile(0.95, sum by (le) (rate(crowdpulse_event_processing_latency_seconds_bucket[30s])))`: p95 de `processedAt − receivedAt` |
| 4. ¿Se acumulan mensajes en Kafka? | `kafka_consumer_fetch_manager_records_lag` (ahora, por partición) y `..._records_lag_max` (máximo reciente) |
| 5. ¿Cómo está cada zona? | `100 * crowdpulse_zone_occupancy / crowdpulse_zone_capacity`, con líneas en el 85 % y el 95 % |
| 6. ¿Espera el `processor` a la base de datos? | `crowdpulse_database_outage` (1/0) y `crowdpulse_zone_alert_level` (0/1/2) |

- `rate(x[30s])` es cuánto sube un contador por segundo, de media en los últimos 30 s.
- `histogram_quantile` calcula un percentil a partir de las cubetas del histograma: con
  0.95, el tiempo por debajo del cual queda el 95 % de los eventos.
- Un evento cuenta en el `processor` solo cuando su transacción se ha confirmado: un intento
  revertido y reintentado cuenta una vez.
- La ocupación del panel **se lee de PostgreSQL** cada 5 s (no de la memoria del
  `processor`), así que coincide con `GET /api/v1/zones` y sobrevive a un reinicio. Puede ir
  hasta 5 s por detrás.
- La latencia usa solo relojes del sistema. `receivedAt − occurredAt` (reloj del sensor) va
  aparte, en `crowdpulse_event_sensor_delay_seconds`.

**Qué se ve en una caída de PostgreSQL** (simulador a 100 ev/s, pausa de 30 s): los
aplicados caen a 0, `crowdpulse_database_outage` pasa a 1 y la API responde algún `503`. Al
volver, la API recibe de golpe lo que los sensores reintentaban (~160 ev/s), el p95 de la
latencia sube a unos segundos y el `processor` se pone al día enseguida. **El retraso en
Kafka apenas sube**: con la base de datos caída la API tampoco acepta eventos (necesita la
base de datos para validar la zona), así que la cola se forma en los sensores, no en Kafka.

#### Trazas ([spec 007](specs/007-trazas/spec.md))

**Jaeger** (`http://localhost:16686`, solo en este equipo) recibe por OTLP (`127.0.0.1:4318`)
las trazas de la API y del `processor`. Cada evento aceptado deja **una** traza:

```
http post /api/v1/events                 (api)        crowdpulse.event.id, zone.id, event.type; status
└─ crowdpulse.sensor-events send         (api)        el contexto viaja en la cabecera traceparent
   └─ crowdpulse.sensor-events process   (processor)  uno por intento; crowdpulse.outcome =
      │                                               applied | duplicate | exit_at_zero
      └─ crowdpulse.dead.letter          (processor)  solo si va al DLT: outcome=dead_lettered, reason
         └─ crowdpulse.sensor-events.dlt send
```

- **Buscar un evento:** en Jaeger, *Service* `crowdpulse-api` o `crowdpulse-processor` y en
  *Tags* `crowdpulse.event.id=<eventId>`. El `eventId` sale en los logs; cada línea de log
  escrita durante el recorrido lleva también su `traceId`, que se puede pegar en la búsqueda.
- Un intento fallido (p. ej. la base de datos caída) es un tramo con error en la misma traza;
  el bueno lleva el `outcome`. Un duplicado o un reproceso del DLT es otra traza.
- Si Jaeger no está o no responde, los tramos se pierden y los eventos no: se envían desde un
  hilo aparte. `/actuator/*` no genera trazas.
- Se guardan todas las trazas; `CROWDPULSE_TRACING_SAMPLING=0.1` guardaría una de cada diez.
  Jaeger las tiene en memoria: se pierden al pararlo.

## Cómo se trabaja
Spec-driven: cada cambio parte de `specs/NNN-nombre/` (`spec.md` → `plan.md` →
`tasks.md`), aprobados por el autor antes de escribir código. Ver [`CLAUDE.md`](CLAUDE.md).

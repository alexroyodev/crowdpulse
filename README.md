# CrowdPulse

Estimación en tiempo real de la ocupación por zonas de un estadio a partir de eventos de
sensores de entrada y salida. Proyecto de aprendizaje y portfolio: lo importante es
poder demostrar cómo se comporta ante duplicados, desorden, concurrencia y reinicios.

**Estado:** fase 3 (fiabilidad) — specs [001 (ingesta)](specs/001-ingesta-mvp/spec.md),
[002 (alertas)](specs/002-alertas-umbrales/spec.md), [003 (Kafka)](specs/003-procesamiento-asincrono/spec.md)
y [004 (caídas)](specs/004-fiabilidad-ante-caidas/spec.md) terminadas.

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
- **PostgreSQL 18** y **Apache Kafka 4.2** (KRaft); en local, con Docker Compose.

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
| Sin base de datos la API responde `503`, nunca `500` | `DatabaseDownIT` |
| El estado y las alertas sobreviven a un reinicio | `ProcessorRestartIT` |
| Errores en formato `ProblemDetail` (RFC 9457), en español y sin trazas | `ApiErrorsIT`, `EventIngestionIT` |
| Alerta al llegar al 85 % (`WARNING`) y al 95 % (`CRITICAL`), con histéresis de 5 puntos: oscilar en el umbral genera un solo cambio | `AlertPolicyTest`, `AlertProcessingIT` |
| Evento, ocupación y cambio de nivel se guardan juntos o ninguno | `AlertAtomicityIT` |
| Eventos concurrentes que cruzan un umbral lo registran una sola vez | `ConcurrentAlertIT` |

Cada test de garantía se ha validado una vez quitando el mecanismo que la da y viéndolo
fallar. Las decisiones técnicas están en [`docs/adr/`](docs/adr/) (la de Kafka, en el
[ADR 0004](docs/adr/0004-kafka-entre-api-y-processor.md); la de las caídas, en el
[ADR 0005](docs/adr/0005-fallo-de-infraestructura-frente-a-mensaje-malo.md)) y los principios del proyecto en
[`docs/constitution.md`](docs/constitution.md).

## Requisitos
- **JDK 21**, con `JAVA_HOME` apuntando a él (`./mvnw` lo usa).
- **Docker** en marcha (lo necesitan los tests de integración y las aplicaciones en local).
- El puerto **9092** libre: Kafka se publica ahí en local.

## Uso

```bash
./mvnw test                   # tests unitarios (sin Docker)
./mvnw verify                 # todos, incluidos los de integración con Testcontainers

docker compose up -d                  # PostgreSQL y Kafka
./mvnw -pl api spring-boot:run        # API en el 8080 (otra terminal)
./mvnw -pl processor spring-boot:run  # processor en el 8081 (otra terminal)
curl localhost:8080/actuator/health
curl localhost:8081/actuator/health
```

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
principio y más salidas al final. Al terminar espera a que la API refleje los eventos
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
curl -X POST localhost:8081/admin/dead-letters/replay     # {"replayed": n}
```

Un mensaje que sigue sin poder procesarse vuelve al DLT y se reenviará en cada llamada.
El endpoint no tiene autenticación: el puerto 8081 debe quedarse en local.

## Cómo se trabaja
Spec-driven: cada cambio parte de `specs/NNN-nombre/` (`spec.md` → `plan.md` →
`tasks.md`), aprobados por el autor antes de escribir código. Ver [`CLAUDE.md`](CLAUDE.md).

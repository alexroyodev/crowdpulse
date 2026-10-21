# CrowdPulse

Estimación en tiempo real de la ocupación por zonas de un estadio a partir de eventos de
sensores de entrada y salida. Proyecto de aprendizaje y portfolio: lo importante es
poder demostrar cómo se comporta ante duplicados, desorden, concurrencia y reinicios.

**Estado:** fase 1 (MVP síncrono) — specs [001 (ingesta)](specs/001-ingesta-mvp/spec.md) y
[002 (alertas)](specs/002-alertas-umbrales/spec.md) terminadas.

```
simulator ──HTTP POST /api/v1/events──► api ──JdbcClient──► PostgreSQL
                                         ▲
                      GET /api/v1/zones ─┘
```

- **`api/`** — Spring Boot 4 (Java 21). Valida cada evento, actualiza la ocupación de su
  zona y su nivel de alerta en la misma transacción, y los expone por HTTP.
- **`simulator/`** — Java sin dependencias. Genera tráfico de llegada y salida de público
  y comprueba al final que la API cuadra.
- **PostgreSQL 18** con migraciones Flyway; en local, con Docker Compose.

## Garantías (y el test que las demuestra)

| Garantía | Test |
|----------|------|
| Un `eventId` repetido se acepta (202) pero no cuenta dos veces | `EventIngestionIT`, `ConcurrentIngestionIT` |
| Evento y ocupación se guardan juntos o ninguno (una transacción) | `TransactionAtomicityIT` |
| 1 000 eventos concurrentes en una zona → exactamente 1 000 | `ConcurrentIngestionIT` |
| La ocupación nunca es negativa: un `EXIT` con la zona vacía se queda en 0 y se marca como anomalía | `OccupancyTest`, `EventIngestionIT` |
| `occurredAt` (tiempo del evento) y `receivedAt` (tiempo del sistema) se guardan por separado | `EventIngestionIT` |
| La ocupación sobrevive a un reinicio de la API | `RestartIT` |
| Errores en formato `ProblemDetail` (RFC 9457), en español y sin trazas | `ApiErrorsIT`, `EventIngestionIT` |
| Alerta al llegar al 85 % (`WARNING`) y al 95 % (`CRITICAL`), con histéresis de 5 puntos: oscilar en el umbral genera un solo cambio | `AlertPolicyTest`, `AlertIT` |
| Evento, ocupación y cambio de nivel se guardan juntos o ninguno | `AlertAtomicityIT` |
| Peticiones concurrentes que cruzan un umbral lo registran una sola vez | `ConcurrentAlertIT` |
| El nivel y el histórico de alertas sobreviven a un reinicio | `RestartIT` |

Las decisiones técnicas están en [`docs/adr/`](docs/adr/) y los principios del proyecto
en [`docs/constitution.md`](docs/constitution.md).

## Requisitos
- **JDK 21**, con `JAVA_HOME` apuntando a él (`./mvnw` lo usa).
- **Docker** en marcha (lo necesitan los tests de integración y la API en local).

## Uso

```bash
./mvnw test                   # tests unitarios (sin Docker)
./mvnw verify                 # todos, incluidos los de integración con Testcontainers

docker compose up -d          # PostgreSQL
./mvnw -pl api spring-boot:run
curl localhost:8080/actuator/health
```

### API

```bash
# Registrar un evento -> 202 (también si es un duplicado)
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
| `202` | Evento aceptado, nuevo o duplicado |
| `400` | JSON mal formado, campo ausente o con formato inválido, `occurredAt` más de 5 min en el futuro. El cuerpo incluye `errors: [{field, message}]` |
| `422` | `zoneId` no existe en el catálogo |

Zonas: `NORTE`, `SUR` (20 000 plazas), `ESTE`, `OESTE` (15 000) y `PALCO` (200, para la
demo de alertas).

### Alertas

| Nivel | Se entra al llegar a | Se sale al bajar de |
|-------|----------------------|---------------------|
| `WARNING` | 85 % | 80 % |
| `CRITICAL` | 95 % | 90 % (vuelve a `WARNING`) |

Cada cambio de nivel queda en `GET /api/v1/alerts` y en el log (`WARN "Alert level
changed"`). Los umbrales están en `application.yml` (`crowdpulse.alerts`).

### Simulador

```bash
./mvnw -pl simulator package
java -jar simulator/target/simulator.jar --rate=100 --duration=60s --duplicates=5
java -jar simulator/target/simulator.jar --help
```

Envía los eventos de cada zona en orden desde su propio hilo, con más entradas al
principio y más salidas al final. Al terminar compara el cambio de ocupación de cada
zona en la API con el esperado (entradas − salidas únicas) y sale con código `0` si todo
cuadra y todas las respuestas fueron `202`.

Demo de alertas: `--zones=PALCO --rate=20 --duration=60s` lleva el palco al 85 % y al 95 %
en unos segundos; después, `curl 'localhost:8080/api/v1/alerts?zoneId=PALCO'`.

> En Git Bash (Windows) añade `-Dstdout.encoding=UTF-8` para ver bien los acentos.

## Cómo se trabaja
Spec-driven: cada cambio parte de `specs/NNN-nombre/` (`spec.md` → `plan.md` →
`tasks.md`), aprobados por el autor antes de escribir código. Ver [`CLAUDE.md`](CLAUDE.md).

# MEMORY.md — Estado de CrowdPulse

## Estado actual
- Fase 4 en curso. Specs 001–006 terminadas; verificación de los CA al final de cada
  `tasks.md`. 006 = métricas (Prometheus/Grafana). Siguen trazas (OpenTelemetry) y
  dashboard React, cada una en su spec. Sin spec activa.
- Código: monorepo Maven; módulos `shared/` (migraciones, enums, `SensorEventMessage` v1,
  `Topics`), `api/` (valida, 422, publica; consultas), `processor/` (8081 local, actuator en
  8082; `EventProcessor`: reglas de 001 y 002) y `simulator/`. `observability/`: config de
  Prometheus y Grafana.

## Decisiones (y su porqué)
- Maven, Java 21, Flyway, sin Lombok: código explícito y fácil de defender.
- Caídas (ADR 0005): `DatabaseUnavailable` (`shared`) separa «BD caída» (reintento sin
  límite 1→30 s, `WARN` al caer y al volver) de «mensaje malo» (DLT); estado SQL `08…`
  incluido. API sin BD → 503. Reproceso: `POST /admin/dead-letters/replay` (salta fallos
  de contenido; `?all=true` todo). `processor` solo en 127.0.0.1 (spec 005).
- Kafka (ADR 0004): clave `zoneId`, 3 particiones; la API espera el ack (si no, 503);
  offset tras el commit (`ack-mode: record`); idempotencia por `eventId`; DLT tras 3
  intentos (directo si no es reintentable). `idIsGroup = false` en el listener.
- JdbcClient en vez de JPA (ADR 0001); bloqueo pesimista `FOR UPDATE` por zona
  (ADR 0002). Un `EXIT` anómalo sí actualiza `lastEventAt`.
- Alertas (ADR 0003): `AlertPolicy` pura en la transacción del evento; 85/80 y 95/90;
  histórico en `alert` (`event_id UNIQUE`, orden por `seq`).
- Configuración propia en records `@ConfigurationProperties`, no `@Value` (salvo el periodo
  de `@Scheduled`, que lo lee la propia anotación).
- Métricas (spec 006, ADR 0006): se cuenta tras el commit (`process` devuelve `Processed`);
  gauges de zona leídos de la BD cada 5 s; peticiones de la API con la métrica HTTP de
  Spring Boot, sin contador propio. Prometheus/Grafana solo en `127.0.0.1`.
- Zona inexistente: 404 en GET, 422 en POST. Textos de la API en español
  (`messages.properties`, locale fijo `es`, también Bean Validation).
- Logs con `eventId`: duplicado → DEBUG; zona desconocida, EXIT en 0 y 400 → WARN.
- Instantes de la API en UTC con milisegundos (`@JsonFormat`); la BD guarda microsegundos.
- Pruebas de mutación: en todas las specs; detalle en cada `tasks.md`.
- Simulador: un hilo por zona (orden por zona); reintenta 503; comprueba que la API cuadra.
- Migraciones (en `shared`): V1 esquema, V2 4 zonas, V3 alertas, V4 `PALCO`, V5
  `processed_at` (filas antiguas = `received_at`). No editarlas: cambios en V6+.

## Problemas abiertos
- Sin autenticación real en el `processor` (solo escucha en local): pendiente para cuando
  haya despliegue.

## Errores a evitar
- Tests de «a mitad»: no depender del tiempo (lotes de 10, o construir la mitad publicando
  en dos tandas); un consumidor creado a mano confirma solo si no se desactiva.
- Espiar un bean `@Transactional` con `@MockitoSpyBean`: el espía queda dentro de la
  transacción; para «fallar tras el commit» usa `afterCommit` (T09 de la spec 003).
- Tests que arrancan el `processor` a mano (`SpringApplicationBuilder`): puertos aleatorios
  para `server.port` **y** `management.server.port`, o chocan en el 8082.
- Con la BD caída la API tampoco acepta (valida la zona): la cola se forma en los sensores,
  no en Kafka.
- Tras mover recursos de un módulo a otro, `./mvnw clean`: `target/classes` guarda las
  copias viejas y los tests pasan con ellas (pasó en T01 con `db/migration`).
- Git Bash sin `uuidgen`: `powershell.exe -NoProfile -Command '[guid]::NewGuid()'`.
- Notas de Windows (java 17 en el PATH, `java.exe` vivo en el 8080, acentos en Git
  Bash, Docker Desktop parado): ver `CLAUDE.md` › Comandos.

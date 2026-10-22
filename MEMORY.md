# MEMORY.md — Estado de CrowdPulse

## Estado actual
- Fase 2 cumplida (2026-10-22). Specs 001, 002 y 003 terminadas; verificación de los CA
  al final de cada `tasks.md`.
- Spec activa: `specs/004-fiabilidad-ante-caidas/` (fase 3): D1–D4 resueltas (BD caída:
  reintento sin límite 1→30 s; replay del DLT por endpoint; API 503 sin BD; constitución
  enmendada). Spec y plan aprobados (`DatabaseUnavailable` en `shared`, con spring-tx/jdbc).
  `tasks.md` aprobado. Hecho: T01–T05. Siguiente: T06.
- Código: monorepo Maven; módulos `shared/` (migraciones, enums, `SensorEventMessage` v1,
  `Topics`), `api/` (valida, 422, publica; consultas), `processor/` (8081, `EventProcessor`:
  reglas de 001 y 002) y `simulator/`.

## Decisiones (y su porqué)
- Maven, Java 21, Flyway, sin Lombok: código explícito y fácil de defender.
- Kafka (ADR 0004): clave `zoneId`, 3 particiones; la API espera el ack (si no, 503);
  offset tras el commit (`ack-mode: record`); idempotencia por `eventId`; DLT tras 3
  intentos (directo si no es reintentable). `idIsGroup = false` en el listener.
- JdbcClient en vez de JPA (ADR 0001); bloqueo pesimista `FOR UPDATE` por zona
  (ADR 0002). Un `EXIT` anómalo sí actualiza `lastEventAt`.
- Alertas (ADR 0003): nivel decidido por `AlertPolicy` (pura, enteros exactos) dentro
  de la transacción del evento y guardado en la fila bloqueada; 85/80 y 95/90 en
  `crowdpulse.alerts`; histórico en `alert` (`event_id UNIQUE`, orden por `seq`).
- Configuración propia en records `@ConfigurationProperties`, no `@Value`.
- Zona inexistente: `ZoneNotFoundException` (GET, 404) / `UnknownZoneException`
  (POST, 422): misma causa, distinto significado HTTP.
- Textos de la API siempre en español: `messages.properties` (`problemDetail.*`) y
  locale fijo `es`, también para Bean Validation. Aprobado por el autor.
- Logs con `eventId`: duplicado → DEBUG (esperables con at-least-once); zona
  desconocida, EXIT en 0 y 400 → WARN.
- Instantes de la API en UTC con milisegundos (`@JsonFormat`); la BD guarda microsegundos.
- Pruebas de mutación: hechas en 001–003; detalle en cada `tasks.md`.
- Simulador: un hilo por zona (orden por zona); P(ENTRY) 0,9→0,3; espera hasta 30 s a
  que la API cuadre y comprueba CA10 solo.
- Migraciones (en `shared`): V1 esquema, V2 4 zonas, V3 alertas, V4 `PALCO`, V5
  `processed_at` (filas antiguas = `received_at`). No editarlas: cambios en V6+.

## Problemas abiertos
- Con PostgreSQL caído más de ~3 s, los eventos van al DLT y la ocupación queda mal hasta
  reprocesarlos (no hay herramienta de reproceso). Aceptado en fase 2; es de la fase 3.

## Errores a evitar
- Espiar un bean `@Transactional` con `@MockitoSpyBean`: el espía queda dentro de la
  transacción; para «fallar tras el commit» usa `afterCommit` (T09 de la spec 003).
- Tras mover recursos de un módulo a otro, `./mvnw clean`: `target/classes` guarda las
  copias viejas y los tests pasan con ellas (pasó en T01 con `db/migration`).
- Git Bash no tiene `/proc/sys/kernel/random/uuid` ni `uuidgen`: UUIDs con
  `powershell.exe -NoProfile -Command '[guid]::NewGuid().ToString()' | tr -d '
'`.
- Notas de Windows (java 17 en el PATH, `java.exe` vivo en el 8080, acentos en Git
  Bash, Docker Desktop parado): ver `CLAUDE.md` › Comandos.

# MEMORY.md — Estado de CrowdPulse

## Estado actual
- Fase 2 en curso. Specs 001 y 002 terminadas (2026-10-21; verificación de los CA al
  final de su `tasks.md`).
- Spec activa: `specs/003-procesamiento-asincrono/`: spec, plan y tareas aprobados
  (2026-10-22), con permiso para `shared`, `processor`, Kafka, 9092 fijo y V5.
  Hecho: T01–T06. Siguiente: T07. Kafka `apache/kafka:4.2.2`. Hasta T07 no hay camino de
  extremo a extremo (la API publica, nadie consume todavía).
- Código: monorepo Maven; módulos `shared/` (migraciones, enums, `SensorEventMessage` v1,
  `Topics`), `api/` (valida, 422, publica; consultas), `processor/` (8081, `EventProcessor`:
  reglas de 001 y 002) y `simulator/`.

## Decisiones (y su porqué)
- Maven, Java 21, Flyway, sin Lombok: código explícito y fácil de defender.
- `POST /events` responde 202 desde la fase 1: el contrato no cambia con Kafka.
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
- Pruebas de mutación hechas en 001 y 002 (sin `FOR UPDATE`, sin `@Transactional`, sin
  histéresis): todas fallaron como debían; detalle al final de cada `tasks.md`.
- Simulador: un hilo por zona con envío síncrono (orden por zona); P(ENTRY) 0,9→0,3
  para que al final quede gente y CA10 compare números reales; comprueba CA10 solo.
- Migraciones (en `shared`): V1 esquema, V2 4 zonas, V3 alertas, V4 `PALCO`, V5
  `processed_at` (filas antiguas = `received_at`). No editarlas: cambios en V6+.

## Problemas abiertos
- Spec 003: con PostgreSQL caído más de ~3 s, los eventos van al DLT y la ocupación queda
  mal hasta reprocesarlos. Aceptado para la fase 2; distinguirlo es de la fase 3.

## Errores a evitar
- `-Dit.test=X` con módulos: `-pl shared,api` (o `,processor`) y
  `-Dfailsafe.failIfNoSpecifiedTests=false`; sin `shared` en el reactor no resuelve.
- Tras mover recursos de un módulo a otro, `./mvnw clean`: `target/classes` guarda las
  copias viejas y los tests pasan con ellas (pasó en T01 con `db/migration`).
- Git Bash no tiene `/proc/sys/kernel/random/uuid` ni `uuidgen`: UUIDs con
  `powershell.exe -NoProfile -Command '[guid]::NewGuid().ToString()' | tr -d '
'`.
- Notas de Windows (java 17 en el PATH, `java.exe` vivo en el 8080, acentos en Git
  Bash, Docker Desktop parado): ver `CLAUDE.md` › Comandos.

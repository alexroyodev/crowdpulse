# MEMORY.md — Estado de CrowdPulse

## Estado actual
- Fase: 1 (MVP síncrono, sin Kafka). Objetivo de la fase cumplido.
- Specs 001 (ingesta) y 002 (alertas): **terminadas** (2026-10-21); la verificación de
  cada CA está al final de su `tasks.md`.
- Spec activa: `specs/003-procesamiento-asincrono/` (fase 2): spec y plan aprobados
  (2026-10-22), con los permisos que pedía el plan (módulos `shared` y `processor`, Kafka,
  `compose.yaml` con 9092 fijo, V5). `tasks.md` (T01–T11) aprobado; siguiente: T01.
- Código: monorepo Maven; `pom.xml` raíz es el padre; módulos `api/` (Spring Boot 4.1)
  y `simulator/` (Java plano). 121 tests (`./mvnw verify`).

## Decisiones (y su porqué)
- Maven y Java 21 LTS; Flyway desde el día 1; sin Lombok: código explícito y
  versionado, fácil de defender en entrevista.
- `POST /events` responde 202 desde la fase 1: el contrato no cambia con Kafka.
- JdbcClient en vez de JPA (ADR 0001); bloqueo pesimista `FOR UPDATE` por zona
  (ADR 0002). Un `EXIT` anómalo sí actualiza `lastEventAt`.
- Alertas (ADR 0003): nivel decidido por `AlertPolicy` (pura, enteros exactos) dentro
  de la transacción del evento y guardado en la fila bloqueada; 85/80 y 95/90 en
  `crowdpulse.alerts`; histórico en `alert` (`event_id UNIQUE`, orden por `seq`).
- `occupancy` usa `event.EventType`: la ocupación es función de los eventos.
- Configuración propia en records `@ConfigurationProperties`, no `@Value`.
- Zona inexistente: `ZoneNotFoundException` (GET, 404) / `UnknownZoneException`
  (POST, 422): misma causa, distinto significado HTTP.
- Textos de la API siempre en español: `messages.properties` (`problemDetail.*`) y
  locale fijo `es`, también para Bean Validation. Aprobado por el autor.
- Logs con `eventId`: duplicado → DEBUG (esperables con at-least-once); zona
  desconocida, EXIT en 0 y 400 → WARN.
- `lastEventAt` siempre en UTC con milisegundos (`@JsonFormat` en `ZoneView`); la BD
  guarda microsegundos.
- Pruebas de mutación hechas en 001 y 002 (sin `FOR UPDATE`, sin `@Transactional`, sin
  histéresis): todas fallaron como debían; detalle al final de cada `tasks.md`.
- `Concurrently.run` (test): utilidad compartida para lanzar N hilos a la vez.
- Simulador: un hilo por zona con envío síncrono (orden por zona); P(ENTRY) 0,9→0,3
  para que al final quede gente y CA10 compare números reales; comprueba CA10 solo.
- Migraciones aplicadas: V1 (esquema), V2 (4 zonas), V3 (nivel de alerta + tabla
  `alert`), V4 (zona `PALCO`). No editarlas: cambios en V5+.

## Problemas abiertos
- Spec 003: con PostgreSQL caído más de ~3 s, los eventos van al DLT y la ocupación queda
  mal hasta reprocesarlos. Aceptado para la fase 2; distinguirlo es de la fase 3.

## Errores a evitar
- Git Bash no tiene `/proc/sys/kernel/random/uuid` ni `uuidgen`: UUIDs con
  `powershell.exe -NoProfile -Command '[guid]::NewGuid().ToString()' | tr -d '
'`.
- Notas de Windows (java 17 en el PATH, `java.exe` vivo en el 8080, acentos en Git
  Bash, Docker Desktop parado): ver `CLAUDE.md` › Comandos.

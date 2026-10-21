# MEMORY.md — Estado de CrowdPulse

## Estado actual
- Fase: 1 (MVP síncrono, sin Kafka). Objetivo de la fase cumplido.
- Spec 001 (`specs/001-ingesta-mvp/`): **terminada** (2026-10-21). CA1–CA11
  verificados; tabla al final de `tasks.md`.
- Spec activa: `specs/002-alertas-umbrales/` — spec y plan aprobados (2026-10-21,
  incluidas V3 y V4) y tareas aprobadas. T01 hecha. Siguiente paso: T02.
- Código: monorepo Maven; `pom.xml` raíz es el padre; módulos `api/` (Spring Boot 4.1)
  y `simulator/` (Java plano). 80 tests (`./mvnw verify`).

## Decisiones (y su porqué)
- Maven y Java 21 LTS; Flyway desde el día 1; sin Lombok: código explícito y
  versionado, fácil de defender en entrevista.
- `POST /events` responde 202 desde la fase 1: el contrato no cambia con Kafka.
- JdbcClient en vez de JPA (ADR 0001); bloqueo pesimista `FOR UPDATE` por zona
  (ADR 0002). Un `EXIT` anómalo sí actualiza `lastEventAt`.
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
- Pruebas de mutación hechas: sin `FOR UPDATE` → 169 de 1 000; sin `@Transactional`
  → el evento queda guardado. Duplicados concurrentes: los protege la PK, no el bloqueo.
- Simulador: un hilo por zona con envío síncrono (orden por zona); P(ENTRY) 0,9→0,3
  para que al final quede gente y CA10 compare números reales; comprueba CA10 solo.
- Migraciones aplicadas: V1 (esquema), V2 (4 zonas), V3 (nivel de alerta + tabla
  `alert`), V4 (zona `PALCO`). No editarlas: cambios en V5+.

## Problemas abiertos
- (ninguno)

## Errores a evitar
- Notas de Windows (java 17 en el PATH, `java.exe` vivo en el 8080, acentos en Git
  Bash, Docker Desktop parado): ver `CLAUDE.md` › Comandos.

# MEMORY.md — Estado de CrowdPulse

## Estado actual
- Fase: 1 (MVP síncrono, sin Kafka).
- Spec activa: `specs/001-ingesta-mvp/` — spec, plan y tareas aprobados; en curso T01.
- Siguiente paso: T01 (monorepo).
- Código: proyecto Spring Boot 4.1.1 generado en la raíz; el plan propone moverlo a
  `api/` y crear `simulator/`.

## Decisiones (y su porqué)
- Maven y Java 21 LTS: herramienta más habitual en ofertas backend Java; LTS estable.
- Flyway desde el día 1 en lugar de `ddl-auto`: el esquema queda versionado y
  revisable.
- Sin Lombok: records y código explícito, más fácil de defender en entrevista.
- `POST /events` responde 202 desde la fase 1: el contrato no cambia cuando el
  procesamiento pase a ser asíncrono con Kafka.
- Plan 001 (aprobado 2026-10-19): JdbcClient en vez de JPA (el SQL crítico es
  específico de PostgreSQL); bloqueo pesimista `FOR UPDATE` por zona; módulos `api/`
  + `simulator/` (Java plano); `postgres:18` fijado; un `EXIT` anómalo sí actualiza
  `lastEventAt`.

## Problemas abiertos
- (ninguno)

## Errores a evitar
- (vacío por ahora)

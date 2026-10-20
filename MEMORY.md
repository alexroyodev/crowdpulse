# MEMORY.md — Estado de CrowdPulse

## Estado actual
- Fase: 1 (MVP síncrono, sin Kafka).
- Spec activa: `specs/001-ingesta-mvp/` — spec, plan y tareas aprobados. T01–T05 hechas.
- Siguiente paso: T06 (`ZoneView` y `occupancyPct`, `ZoneViewTest`).
- Código: monorepo Maven; `pom.xml` raíz es el padre (`packaging=pom`) y módulo
  `api/` con el proyecto de Initializr. `simulator/` se añade en T13.

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
- `occupancy` usa `event.EventType` (única dependencia hacia `event`): la ocupación
  es función de los eventos. `Occupancy` es inmutable y valida `value >= 0`.
- Migraciones aplicadas: V1 (esquema), V2 (4 zonas). No editarlas: cualquier cambio
  va en V3+.

## Problemas abiertos
- (ninguno)

## Errores a evitar
- En Windows, parar `./mvnw spring-boot:run` deja vivo el `java.exe` hijo en el 8080:
  localizar el PID con `netstat -ano | grep :8080` y pararlo.
- El `java` del PATH es 17; `./mvnw` usa `JAVA_HOME` (JDK 21). No ejecutar `java`
  directamente sin comprobar la versión.

# MEMORY.md — Estado de CrowdPulse

## Estado actual
- Fase: 1 (MVP síncrono, sin Kafka).
- Spec activa: `specs/001-ingesta-mvp/` — spec, plan y tareas aprobados. T01–T16 hechas (API, simulador, ADRs).
- Siguiente paso: T17 (README, CA11 desde clon limpio, cierre de la spec).
- Código: monorepo Maven; `pom.xml` raíz es el padre; módulos `api/` y `simulator/`
  (Java plano, solo JUnit en test, JAR `simulator/target/simulator.jar`).

## Decisiones (y su porqué)
- Maven y Java 21 LTS: herramienta más habitual en ofertas backend Java; LTS estable.
- Flyway desde el día 1 en lugar de `ddl-auto`: el esquema queda versionado y
  revisable.
- Sin Lombok: records y código explícito, más fácil de defender en entrevista.
- `POST /events` responde 202 desde la fase 1: el contrato no cambia cuando el
  procesamiento pase a ser asíncrono con Kafka.
- Plan 001 (aprobado 2026-10-19): JdbcClient en vez de JPA (ADR 0001); bloqueo
  pesimista `FOR UPDATE` por zona (ADR 0002); módulos `api/`
  + `simulator/` (Java plano); `postgres:18` fijado; un `EXIT` anómalo sí actualiza
  `lastEventAt`.
- `occupancy` usa `event.EventType` (única dependencia hacia `event`): la ocupación
  es función de los eventos. `Occupancy` es inmutable y valida `value >= 0`.
- Configuración propia en records `@ConfigurationProperties` (p. ej. `EventProperties`)
  en vez de `@Value`: tipada y validada al arrancar.
- Zona inexistente: `ZoneNotFoundException` (GET, 404) y `UnknownZoneException`
  (POST, 422): misma causa, distinto significado HTTP.
- ITs comparten contexto y contenedor: los que cambian datos usan `@Sql`
  `reset-state.sql` antes y después de cada test.
- Errores de Spring MVC traducidos en `messages.properties` (`problemDetail.*`); siempre
  en español: locale fijo `es` (`spring.web.locale-resolver: fixed`), también para los
  mensajes de Bean Validation.
- Duplicado → log INFO; zona desconocida y EXIT en 0 → log WARN; siempre con `eventId`.
- CA6 verificado con mutación: sin `FOR UPDATE`, 1 000 ENTRY concurrentes → 169.
  Los duplicados concurrentes los protege la PK (`ON CONFLICT`), no el bloqueo.
- Migraciones aplicadas: V1 (esquema), V2 (4 zonas). No editarlas: cualquier cambio
  va en V3+.

## Problemas abiertos
- Demo CA10 OK (6 000 eventos, 202=6000), pero con P(ENTRY) 0,9→0,1 el estadio acaba
  casi vacío y la comprobación final es débil. Propuesto 0,9→0,3: pendiente del autor.

## Errores a evitar
- En Windows, parar `./mvnw spring-boot:run` deja vivo el `java.exe` hijo en el 8080:
  localizar el PID con `netstat -ano | grep :8080` y pararlo.
- Consola de Windows: el simulador escribe en la codificación nativa; en Git Bash
  usar `-Dstdout.encoding=UTF-8` para ver bien los acentos.
- El `java` del PATH es 17; `./mvnw` usa `JAVA_HOME` (JDK 21). No ejecutar `java`
  directamente sin comprobar la versión.

# MEMORY.md — Estado de CrowdPulse

## Estado actual
- Fase: 1 (MVP síncrono, sin Kafka).
- Spec 001 (`specs/001-ingesta-mvp/`): T01–T17 hechas, CA1–CA11 verificados (tabla al
  final de `tasks.md`). Pendiente: visto bueno del autor para darla por cerrada.
- Siguiente paso: decidir los problemas abiertos y redactar la spec 002.
- Código: monorepo Maven; `pom.xml` raíz es el padre; módulos `api/` (Spring Boot 4.1)
  y `simulator/` (Java plano, JAR `simulator/target/simulator.jar`). 78 tests.

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
  locale fijo `es`, también para Bean Validation.
- Logs con `eventId`: duplicado → INFO; zona desconocida, EXIT en 0, 400 → WARN.
- Toda garantía con test y prueba de mutación: sin `FOR UPDATE` → 169 de 1 000;
  sin `@Transactional` → el evento queda guardado. Duplicados concurrentes: los
  protege la PK (`ON CONFLICT`), no el bloqueo.
- ITs comparten contexto y contenedor: los que cambian datos usan `@Sql`
  `reset-state.sql` antes y después de cada test.
- Simulador: un hilo por zona con envío síncrono (orden por zona) y comprobación
  automática de CA10 (cambio de ocupación en la API = esperado).
- Migraciones aplicadas: V1 (esquema), V2 (4 zonas). No editarlas: cambios en V3+.

## Problemas abiertos
- Demo CA10 OK, pero con P(ENTRY) 0,9→0,1 el estadio acaba casi vacío y la
  comprobación final es débil (una API que ignorase eventos cuadraría en zonas que
  acaban en 0). Propuesto 0,9→0,3: pendiente del autor.
- Duplicados en log INFO: con mucho tráfico generan ruido; ¿bajar a DEBUG?
- `lastEventAt` omite los milisegundos cuando son `.000` (ISO-8601 válido, pero
  distinto del ejemplo de la spec). ¿Fijar el formato?

## Errores a evitar
- Windows: parar `./mvnw spring-boot:run` deja vivo el `java.exe` hijo en el 8080
  (buscar el PID con `netstat -ano | grep :8080`).
- Windows: el `java` del PATH es 17; usar el de `JAVA_HOME` (21). En Git Bash, añadir
  `-Dstdout.encoding=UTF-8` al simulador para ver los acentos.
- Docker Desktop no arranca solo tras reiniciar: sin él fallan todos los `*IT`.
- Al hacer pruebas de mutación con `-Dit.test`, usar `-pl api`: en la raíz falla por
  «no tests matching» y parece que el test ha fallado.

# CLAUDE.md — CrowdPulse
Sistema de eventos en tiempo real que estima la ocupación por zonas de un estadio
(sensores de entrada/salida → mensajería → procesadores → dashboard) y genera alertas
cuando se superan umbrales. Proyecto de aprendizaje y portfolio: el autor debe poder
explicar cada línea en una entrevista. Simple y verificable antes que completo.

## Stack y estructura
- Java 21 + Spring Boot (Maven, `./mvnw`), PostgreSQL + Flyway, Docker Compose.
- Fases posteriores: Apache Kafka, React + TypeScript, Micrometer/Prometheus/Grafana,
  OpenTelemetry, GitHub Actions. Kubernetes queda fuera del alcance.
- Monorepo:
  - `api/` — ingesta de eventos (`POST`) y consulta de ocupación (`GET`).
  - `processor/` — consumidor de Kafka: aplica ocupación y alertas (fase 2).
  - `shared/` — lo que comparten `api` y `processor`: migraciones Flyway, enums y el
    mensaje `SensorEventMessage`.
  - `simulator/` — generador de eventos de sensores.
  - `dashboard/` — React + TS (fase 4).
  - `compose.yaml` — PostgreSQL y Kafka (puerto fijo 9092); más adelante, observabilidad.
  - `docs/` — `constitution.md`, `architecture.md`, ADRs en `docs/adr/`.
  - `specs/NNN-nombre/` — `spec.md`, `plan.md`, `tasks.md`.
- Paquetes por funcionalidad (`event`, `occupancy`, `alert`, `zone`), no por capa.

## Convenciones
- Código, identificadores y commits en inglés; documentación, specs y textos de la
  interfaz en español.
- Records para DTOs y eventos. Sin Lombok.
- Inyección por constructor. Nada de `@Autowired` en campos.
- Validación con Bean Validation en el borde (controladores); el dominio no recibe datos
  inválidos.
- Errores de la API con `ProblemDetail` (RFC 9457). Nunca devolver trazas de error.
- Configuración en `application.yml`; valores sensibles por variables de entorno.
- El esquema de BD solo cambia con migraciones Flyway nuevas (`V{n}__descripcion.sql`).
  Nunca edites una migración ya aplicada.

## Modelo de evento
- `{ eventId: UUID, sensorId, zoneId, type: ENTRY | EXIT, occurredAt: Instant ISO-8601 UTC }`.
- `eventId` lo genera el sensor y es la clave de idempotencia.
- Al recibirlo se añade `receivedAt` (instante en que lo vio el sistema). Un evento
  nunca se modifica después de aceptarlo.
- Si cambias el contrato del evento, versiónalo y mantén compatibilidad con los
  productores existentes.

## Reglas de dominio (fácil equivocarse)
- Distingue siempre tiempo del evento (`occurredAt`) y tiempo de procesamiento
  (`receivedAt`/`processedAt`). Las ventanas y tasas de negocio usan `occurredAt`;
  la latencia del sistema usa la diferencia entre ambos.
- Usa `Instant` para tiempos. Nunca `LocalDateTime` para eventos. Inyecta `Clock`
  en lugar de llamar a `Instant.now()` directamente, para poder testear.
- Idempotencia: un `eventId` repetido no cambia la ocupación. Registrar el evento
  procesado y actualizar la ocupación ocurre en la misma transacción.
- La ocupación de una zona nunca es negativa. Un `EXIT` que la dejaría por debajo
  de 0 (p. ej. llegó antes que su `ENTRY`) se trata según la spec activa: no
  inventes la política.
- Umbrales de alerta (porcentaje sobre la capacidad de la zona) y su histéresis se
  definen en la spec; no los fijes en el código sin que la spec lo diga.
- Con Kafka: clave del mensaje = `zoneId` (orden garantizado solo por zona). Se
  asume entrega at-least-once: el offset se confirma después de persistir, nunca antes.
  Los mensajes que no se pueden procesar van a un dead-letter topic, no se descartan.
- Kafka no hace fiable el sistema por sí solo: toda garantía (duplicados, orden,
  recuperación) debe tener un test que la demuestre.

## Forma de trabajar (SDD)
- No escribas código sin una spec aprobada en `specs/NNN-*/`.
- Flujo: `spec.md` (qué y por qué, criterios de aceptación) → `plan.md` (cómo,
  decisiones técnicas) → `tasks.md` (pasos pequeños y verificables). Cada paso lo
  revisa el autor antes de pasar al siguiente.
- Implementa una tarea de `tasks.md` cada vez y márcala `[x]` al terminar.
- Si la spec es ambigua o contradice la constitución, para y pregunta.
- Respeta la fase actual (ver `MEMORY.md`): no introduzcas tecnología de una fase
  posterior aunque "vaya a hacer falta".
- Haz solo lo que se pide; cambios pequeños; no reescribas lo que ya funciona.
- Explica las decisiones no obvias: el autor tiene que poder defenderlas.
- Al terminar, resume qué has cambiado y qué decisiones debe revisar.

## Memoria
- Al empezar, lee `MEMORY.md`: fase actual, spec activa, decisiones y errores a evitar.
- Al terminar una tarea, actualízalo: estado, decisiones (con su porqué), problemas
  abiertos.
- Máximo ~50 líneas: resume o elimina lo que ya no aporte.
- Si algo se convierte en regla permanente, propón moverlo a este `CLAUDE.md` o a
  `docs/constitution.md`. Las decisiones de arquitectura relevantes, a un ADR.
- Nunca guardes datos sensibles (claves, tokens, contraseñas).

## Límites
- ✅ Siempre: tests en verde antes de dar una tarea por terminada.
- ✅ Siempre: respetar las reglas de dominio y actualizar `MEMORY.md`.
- ⚠️ Pregunta antes: añadir una dependencia, crear un módulo o servicio nuevo,
  cambiar el contrato del evento o el esquema de la BD, modificar `compose.yaml`.
- 🚫 Nunca: desactivar, borrar o debilitar un test para que pase; usar
  `@Disabled` sin explicación; confirmar secretos; editar migraciones aplicadas;
  añadir Kubernetes o tecnologías fuera del stack.

## Verificación
- Unitarios para la lógica de dominio (ocupación, idempotencia, umbrales) sin Spring.
- Nombres: `*Test` = unitarios (Surefire, `./mvnw test`, sin Docker); `*IT` = integración
  (Failsafe, `./mvnw verify`).
- Integración con Testcontainers (PostgreSQL y, desde la fase 2, Kafka): nada de H2
  ni mocks de la base de datos.
- Cada garantía tiene su test: evento duplicado, `EXIT` antes de `ENTRY`, reinicio
  del consumidor sin perder eventos. Valídalo una vez quitando el mecanismo y viéndolo
  fallar (constitución, principio 2). Un solo `*IT`: `./mvnw verify -pl shared,api` (o
  `shared,processor`) `-Dit.test=X -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
  -Dfailsafe.failIfNoSpecifiedTests=false` (`shared` debe estar en el reactor).
- Los `*IT` comparten contexto y contenedor: si cambian datos, `@Sql("/reset-state.sql")`
  antes y después de cada test.
- Tras cada cambio de la API, levanta el entorno y prueba los endpoints con `curl`.
- Dashboard (fase 4): verifica con el MCP de Chrome DevTools (consola sin errores,
  vista móvil).
- Requiere Docker en marcha para tests de integración y para ejecutar la app.

## Comandos
- Entorno: `docker compose up -d` / `docker compose down -v` (borra datos)
- Tests unitarios: `./mvnw test`
- Todos los tests (incl. integración): `./mvnw verify`
- Ejecutar la API: `./mvnw -pl api spring-boot:run` (puerto 8080)
- Ejecutar el processor: `./mvnw -pl processor spring-boot:run` (solo en `127.0.0.1:8081`;
  en Windows `localhost` puede ir a `::1`: en tests usa `127.0.0.1`)
- Salud: `curl localhost:8080/actuator/health` y `curl 127.0.0.1:8081/actuator/health`
- Simulador: `./mvnw -pl simulator package` y
  `java -jar simulator/target/simulator.jar --rate=100 --duration=60s --duplicates=5`

### Notas de Windows
- El `java` del PATH es 17: usa `$JAVA_HOME/bin/java` (JDK 21) para el simulador.
- Parar `./mvnw spring-boot:run` deja vivo el `java.exe` hijo en el 8080/8081: búscalo
  con `netstat -ano | grep :8080` y páralo.
- Varios `./mvnw verify` a la vez (o en segundo plano) pueden agotar la memoria: cada
  contexto de test levanta su PostgreSQL y su Kafka. Uno cada vez, con compose parado. El
  completo pasa de 10 min: por módulos, `-pl shared,api` y `-pl shared,processor,simulator`.
- Para simular caídas usa `docker compose pause`/`unpause`, no `stop`: PostgreSQL usa un
  puerto aleatorio que cambia al rearrancar y las aplicaciones no lo volverían a encontrar.
- En Git Bash, `-Dstdout.encoding=UTF-8` para ver los acentos del simulador.
- Docker Desktop no arranca solo tras reiniciar: sin él fallan todos los `*IT`.

## Reglas
- Lee `docs/constitution.md` y la spec activa (`specs/NNN-*/`) antes de tocar código.
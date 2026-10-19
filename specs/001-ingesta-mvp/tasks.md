# Tareas 001 — Ingesta de eventos y ocupación por zona (MVP)

- **Estado:** Aprobado (2026-10-19)
- **Spec:** [`spec.md`](spec.md) · **Plan:** [`plan.md`](plan.md)

Una tarea cada vez. Una tarea está terminada cuando su verificación pasa, los tests
están en verde y `MEMORY.md` está actualizado. Al terminar se marca `[x]` y el autor
la revisa antes de empezar la siguiente.

---

## Bloque A — Base del proyecto

- [ ] **T01 — Reorganizar en monorepo** (plan §2)
  - `pom.xml` raíz → `packaging=pom`, `<modules>api</modules>`, hereda de
    `spring-boot-starter-parent` 4.1.1.
  - `git mv src api/src`; crear `api/pom.xml` con las dependencias actuales.
  - Eliminar `HELP.md` (generado por Initializr, sin contenido propio).
  - **Verificación:** `./mvnw verify` en la raíz pasa (`contextLoads` con
    Testcontainers); `git log --follow api/src/main/java/.../CrowdpulseApplication.java`
    conserva el historial.

- [ ] **T02 — Separar unitarios e integración** (plan §6)
  - Configurar `maven-failsafe-plugin` en el padre.
  - Renombrar `CrowdpulseApplicationTests` → `CrowdpulseApplicationIT`.
  - **Verificación:** `./mvnw test` no arranca contenedores; `./mvnw verify` sí
    ejecuta el `*IT`.

- [ ] **T03 — Dependencias, configuración y entorno** (plan §3.2, §3.9, §5)
  - Sustituir `data-jpa` (y `data-jpa-test`) por `spring-boot-starter-jdbc`
    (y su starter de test).
  - `application.properties` → `application.yml`: nombre de la app,
    `spring.docker.compose.file: ../compose.yaml`, actuator solo `health`,
    `server.error.include-stacktrace: never`, `logging.structured.format.console: ecs`,
    `crowdpulse.events.max-future-skew: 5m`.
  - `compose.yaml`: `postgres:18`, base de datos y usuario `crowdpulse`,
    `${POSTGRES_PASSWORD:-crowdpulse}`.
  - `TestcontainersConfiguration`: `postgres:18`.
  - **Verificación:** `./mvnw verify` en verde; `docker compose up -d`,
    `./mvnw -pl api spring-boot:run` y `curl localhost:8080/actuator/health` →
    `{"status":"UP"}`; los logs salen en JSON. (Comprueba el riesgo 5 del plan.)

- [ ] **T04 — Migraciones** (plan §3.3)
  - `V1__create_schema.sql` (tablas `zone`, `zone_occupancy`, `sensor_event`).
  - `V2__seed_zones.sql` (4 zonas + ocupación 0).
  - `MigrationIT`: sobre una base vacía existen las 4 zonas con su capacidad y la
    ocupación a 0; un `INSERT` con `occupancy = -1` falla por el `CHECK`.
  - **Verificación:** `./mvnw verify`; `\dt` con `psql` en el contenedor de compose
    muestra las tablas y `flyway_schema_history`.

## Bloque B — Dominio (sin Spring)

- [ ] **T05 — `Occupancy` y `EventType`** (plan §3.5)
  - `occupancy.Occupancy`, `occupancy.OccupancyChange`, `event.EventType`.
  - `OccupancyTest`: ENTRY suma; EXIT resta; EXIT en 0 → 0 y `anomaly = true`
    (regla 5); supera la capacidad sin tope (regla 6); `lastEventAt` se queda con el
    `occurredAt` más reciente aunque llegue un evento más antiguo; con `lastEventAt`
    nulo toma el del evento.
  - **Verificación:** `./mvnw test`.

- [ ] **T06 — `ZoneView` y `occupancyPct`** (plan §3.8)
  - `zone.ZoneView` con el cálculo estático del porcentaje (`BigDecimal`, 1 decimal,
    `HALF_UP`).
  - `ZoneViewTest`: 12 345/20 000 → 61.7; 0 → 0.0; 25 000/20 000 → 125.0;
    un caso de borde de redondeo (p. ej. 1/8 000 = 0.0125 → 0.0).
  - **Verificación:** `./mvnw test`.

- [ ] **T07 — Validador `@NotTooFarInFuture`** (plan §3.6)
  - Anotación + `ConstraintValidator` con `Clock` y tolerancia por constructor.
  - `common.ClockConfig` con `Clock.systemUTC()`.
  - `NotTooFarInFutureValidatorTest` (CA5) con `Clock.fixed`: +4 min ✔, +5 min ✔,
    +5 min 1 ms ✘, en el pasado ✔, `null` ✔ (de eso se encarga `@NotNull`).
  - **Verificación:** `./mvnw test`.

## Bloque C — API

- [ ] **T08 — Consulta de zonas** (plan §3.7, §3.8)
  - `zone.ZoneQueryRepository` (`JdbcClient`, `JOIN`, orden por `zone_id`).
  - `zone.ZoneController`: `GET /api/v1/zones` y `GET /api/v1/zones/{zoneId}`.
  - `zone.UnknownZoneException`; `common.ApiExceptionHandler` con 404 y 500 genérico.
  - `ZoneQueryIT`: 4 zonas ordenadas con todos los campos y `lastEventAt: null`;
    CA9 (`/NOEXISTE` → 404 `ProblemDetail`); `norte` en minúsculas → 404.
  - **Verificación:** `./mvnw verify`; `curl` a ambos endpoints y al 404.

- [ ] **T09 — Ingesta: camino feliz, duplicados, zona desconocida y anomalía** (plan §3.4)
  - `event.EventRequest`, `event.SensorEvent`, `event.SensorEventRepository`
    (`INSERT ... ON CONFLICT DO NOTHING`), `occupancy.OccupancyRepository`
    (`SELECT ... FOR UPDATE` y `UPDATE`), `event.EventService` (`@Transactional`),
    `event.EventController` (`POST /api/v1/events` → 202).
  - 422 en `ApiExceptionHandler`; log WARN con `eventId` en anomalías.
  - `EventIngestionIT` (con `Clock` fijo `@Primary`):
    CA1 (3 ENTRY + 1 EXIT → 2, `lastEventAt` correcto); CA2 (dos 202, un cambio, una
    fila en `sensor_event`); CA4 (422, nada guardado); CA7 (EXIT en 0 → 202,
    ocupación 0, fila con `anomaly = true`); `receivedAt` = instante del `Clock` fijo.
  - **Verificación:** `./mvnw verify`; `curl` de un ENTRY, el mismo repetido y una
    zona inexistente; el WARN de anomalía aparece en el log en JSON con `eventId`.

- [ ] **T10 — Errores de validación (400)** (plan §3.6, §3.7)
  - `ApiExceptionHandler`: `MethodArgumentNotValidException` y
    `HttpMessageNotReadableException` → 400 con `errors: [{field, message}]`,
    textos en español; log con `eventId` si se pudo leer.
  - `EventIngestionIT`: CA3 (falta `sensorId`; `type: "JUMP"`; `eventId` no UUID;
    `occurredAt` mal formado; JSON roto; `sensorId` de 51 caracteres) → 400 que nombra
    el campo y la ocupación no cambia; CA5 por HTTP (+5 min 1 ms → 400, +4 min → 202);
    campo JSON desconocido → 202.
  - **Verificación:** `./mvnw verify`; `curl` de cada caso; ninguna respuesta contiene
    trazas.

- [ ] **T11 — Concurrencia** (plan §3.4, regla 4)
  - `ConcurrentIngestionIT`: CA6 (1 000 ENTRY distintos a `NORTE` desde 20 hilos →
    1 000); el mismo evento enviado desde 20 hilos a la vez → ocupación 1 y una fila.
  - **Verificación:** `./mvnw verify`, ejecutado 3 veces seguidas en verde (un test de
    concurrencia que pasa una vez no demuestra nada).

- [ ] **T12 — Persistencia tras reinicio** (plan §6.2)
  - `RestartIT`: contenedor estático compartido; arrancar la app, enviar eventos,
    cerrar el contexto, arrancar otro y comprobar la misma ocupación (CA8).
  - **Verificación:** `./mvnw verify`; manual: enviar eventos con `curl`, parar y
    volver a arrancar la API y comparar `GET /api/v1/zones`.

## Bloque D — Simulador

- [ ] **T13 — Módulo `simulator` y configuración** (plan §4)
  - Añadir `simulator` a `<modules>`; `simulator/pom.xml` sin dependencias (salvo
    JUnit para tests) y `maven-jar-plugin` con `Main-Class`.
  - `SimulatorConfig` (record) que parsea `--url`, `--rate`, `--duration`, `--zones`,
    `--duplicates`, con valores por defecto y errores claros.
  - `SimulatorConfigTest`.
  - **Verificación:** `./mvnw test`; `java -jar simulator/target/simulator.jar --rate=abc`
    muestra un error comprensible.

- [ ] **T14 — `TrafficGenerator`** (plan §4.2)
  - Probabilidad de ENTRY de 0,9 → 0,1 según el progreso; ENTRY forzado con ocupación 0;
    reenvío de duplicados con la probabilidad configurada; `sensorId` `{zona}-P01..05`.
  - `TrafficGeneratorTest` (semilla fija): nunca negativa en 10 000 pasos; más ENTRY
    en el primer 10 % y más EXIT en el último 10 %; ratio de duplicados ≈ el
    configurado.
  - **Verificación:** `./mvnw test`.

- [ ] **T15 — Envío, ritmo e informe** (plan §4.2)
  - `EventSender` (`HttpClient`, JSON con *text block*), `SimulationReport`, `Main`
    (un hilo por zona, envío síncrono, ritmo por instante objetivo).
  - **Verificación (CA10):** con compose y la API en marcha,
    `java -jar simulator/target/simulator.jar --rate=100 --duration=60s --duplicates=5`
    → tasa real ≥ 100 ev/s, cero 5xx, y la ocupación esperada por zona coincide con
    `curl localhost:8080/api/v1/zones`.

## Bloque E — Cierre

- [ ] **T16 — ADRs** (plan §8)
  - `docs/adr/0001-jdbcclient-en-lugar-de-jpa.md` y
    `docs/adr/0002-bloqueo-pesimista-por-zona.md` (contexto, decisión, alternativas,
    consecuencias).
  - **Verificación:** revisión del autor.

- [ ] **T17 — Verificación final y documentación**
  - `README.md`: qué es, cómo levantarlo, cómo ejecutar el simulador.
  - CA11: `git clone` en un directorio limpio y `./mvnw verify` con Docker en marcha.
  - Actualizar `MEMORY.md` (spec 001 terminada) y proponer qué reglas pasan a
    `CLAUDE.md` o a la constitución.
  - **Verificación:** CA11 en verde; todos los CA de la spec tienen test o
    verificación manual registrada.

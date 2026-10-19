# Plan 001 — Ingesta de eventos y ocupación por zona (MVP)

- **Estado:** Aprobado (2026-10-19)
- **Spec:** [`spec.md`](spec.md)
- **Fase:** 1
- **Fecha:** 2026-10-19

Este documento describe **cómo** se implementa la spec 001. Cada decisión no obvia
indica la alternativa descartada y por qué. Las marcadas con ⚠️ requieren aprobación
explícita del autor (dependencias, módulos, esquema, `compose.yaml`).

---

## 1. Punto de partida

El repositorio contiene un proyecto generado con Spring Initializr en la raíz:
Spring Boot 4.1.1, Java 21, `pom.xml` único, paquete `com.alexroyodev.crowdpulse`,
starters `webmvc`, `data-jpa`, `flyway`, `validation`, `actuator`,
`docker-compose` y Testcontainers (PostgreSQL). Solo tiene la clase `main` y el test
`contextLoads`.

`CLAUDE.md` define un monorepo con `api/` y `simulator/`, así que lo primero es
reorganizar ese proyecto (sección 2).

## 2. Estructura del repositorio ⚠️ (crea módulos)

```
crowdpulse/
├── pom.xml              # padre/agregador: packaging pom, hereda de spring-boot-starter-parent
├── compose.yaml
├── api/                 # el proyecto actual, movido aquí
│   ├── pom.xml
│   └── src/...
└── simulator/
    ├── pom.xml
    └── src/...
```

- El `pom.xml` raíz pasa a ser `packaging=pom`, hereda de `spring-boot-starter-parent`
  (gestión de versiones para ambos módulos) y declara `<modules>api, simulator</modules>`.
- `api/` recibe el `src/` actual con `git mv`, para conservar el historial.
- `./mvnw verify` en la raíz construye y prueba los dos módulos (CA11).

**Descartado:** dos proyectos Maven independientes. Habría que duplicar la versión de
Java y de Spring Boot, y `./mvnw verify` no cubriría todo con un solo comando.

## 3. Módulo `api`

### 3.1 Paquetes (por funcionalidad)

| Paquete | Contenido |
|---------|-----------|
| `event` | `EventController`, `EventRequest` (record), `EventType` (enum), `EventService`, `SensorEvent` (record), `SensorEventRepository`, validación `@NotTooFarInFuture` |
| `occupancy` | `Occupancy` (record de dominio puro), `OccupancyChange`, `OccupancyRepository` |
| `zone` | `ZoneController`, `ZoneView` (record de respuesta), `ZoneQueryRepository`, `UnknownZoneException` |
| `common` | `ClockConfig` (bean `Clock`), `ApiExceptionHandler` (`ProblemDetail`) |

`event` depende de `occupancy` y de `zone` (para la excepción); `zone` depende de
`occupancy` solo para leer. No hay dependencias en sentido contrario.

### 3.2 Acceso a datos: `JdbcClient` en lugar de JPA ⚠️ (cambia dependencias)

Se sustituye `spring-boot-starter-data-jpa` por `spring-boot-starter-jdbc` (y sus
equivalentes de test) y se usa `JdbcClient` con SQL explícito.

**Por qué:** las dos operaciones críticas de la spec (insertar con
`ON CONFLICT DO NOTHING` para la idempotencia y bloquear la fila con
`SELECT ... FOR UPDATE` para la concurrencia) son SQL específico de PostgreSQL.
Con JPA acabarían igualmente como `@Query(nativeQuery = true)`, conviviendo con
entidades, contexto de persistencia y *dirty checking*. Con `JdbcClient` cada sentencia
que llega a la base de datos está escrita en el código y se puede explicar línea a
línea; los modelos son records inmutables (encaja con «un evento nunca se modifica»).

**Descartado:** JPA/Hibernate. Es más habitual en ofertas, pero aquí aportaría
abstracción sin ahorrar código en lo que importa. Se puede reconsiderar si aparece un
modelo con relaciones ricas.

### 3.3 Esquema de base de datos ⚠️ (crea el esquema)

`V1__create_schema.sql`

```sql
CREATE TABLE zone (
    zone_id   VARCHAR(20)  PRIMARY KEY,
    name      VARCHAR(100) NOT NULL,
    capacity  INTEGER      NOT NULL CHECK (capacity > 0)
);

CREATE TABLE zone_occupancy (
    zone_id        VARCHAR(20) PRIMARY KEY REFERENCES zone (zone_id),
    occupancy      INTEGER     NOT NULL DEFAULT 0 CHECK (occupancy >= 0),
    last_event_at  TIMESTAMPTZ NULL
);

CREATE TABLE sensor_event (
    event_id     UUID        PRIMARY KEY,
    sensor_id    VARCHAR(50) NOT NULL,
    zone_id      VARCHAR(20) NOT NULL REFERENCES zone (zone_id),
    type         VARCHAR(5)  NOT NULL CHECK (type IN ('ENTRY', 'EXIT')),
    occurred_at  TIMESTAMPTZ NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL,
    anomaly      BOOLEAN     NOT NULL DEFAULT FALSE
);
```

`V2__seed_zones.sql`: las 4 zonas de la spec y una fila en `zone_occupancy` con
ocupación 0 para cada una.

Decisiones:
- **Catálogo (`zone`) separado del estado (`zone_occupancy`).** El catálogo es estático
  y lo cambian migraciones; el estado cambia con cada evento. Separarlos deja claro
  qué se bloquea (solo la fila de estado) y en la fase 2 el `processor` escribirá solo
  en `zone_occupancy`. *Descartado:* columna `occupancy` en `zone`: una tabla menos,
  pero mezcla datos de referencia con estado mutable.
- **`event_id` como clave primaria** de `sensor_event`: la propia restricción de
  unicidad es la garantía de idempotencia, no una comprobación previa en Java (que
  tendría una carrera entre «comprobar» e «insertar»).
- **`CHECK (occupancy >= 0)`**: la regla 5 la aplica el dominio, pero la base de datos
  actúa como red de seguridad: si hubiera un fallo de lógica, la transacción falla en
  vez de guardar un estado imposible.
- **`TIMESTAMPTZ`** para todos los instantes (principio 4). Se mapean a `Instant`.
- **`anomaly`** marca el `EXIT` que habría dejado la ocupación en negativo (regla 5).
  Se decide *antes* de insertar el evento, así que la fila nunca se modifica.
- Duplicados: no se guardan (la segunda inserción no hace nada). El histórico (regla 7)
  contiene cada evento único una vez.
- Sin índices adicionales: no hay ninguna consulta en esta spec que los necesite. Se
  añadirán con migración nueva cuando haya recálculo de ocupación.

### 3.4 Flujo de `POST /api/v1/events`

```
EventController  ── @Valid EventRequest ──►  EventService.accept(request)   [@Transactional]
                                               1. SELECT ... FROM zone_occupancy
                                                  WHERE zone_id = ? FOR UPDATE
                                                  └─ sin fila → UnknownZoneException → 422
                                               2. Occupancy.apply(type) → OccupancyChange
                                                  (nueva ocupación + ¿anomalía?)       [dominio puro]
                                               3. INSERT INTO sensor_event ... ON CONFLICT (event_id) DO NOTHING
                                                  └─ 0 filas → duplicado → return (no se toca nada)
                                               4. UPDATE zone_occupancy SET occupancy = ?, last_event_at = ?
                                               5. si anomalía → log WARN con eventId
                                     ◄── 202 Accepted (cuerpo vacío)
```

- **Una transacción** envuelve 1–4 (regla 3): o se guardan evento y ocupación, o nada.
- **Concurrencia (regla 4, CA6): bloqueo pesimista por zona.** `FOR UPDATE` serializa
  los eventos de una misma zona; zonas distintas no se bloquean entre sí. Es el mismo
  modelo que tendremos con Kafka (orden por `zoneId`).
  *Descartados:*
  - Bloqueo optimista con versión y reintentos: con miles de eventos sobre la misma
    zona habría muchos conflictos y un bucle de reintentos que explicar y probar.
  - `UPDATE ... SET occupancy = occupancy + 1` atómico: correcto y rápido, pero mueve
    la regla de negocio (suelo en 0, anomalía) al SQL y no se puede probar sin base de
    datos.
- **Orden de los pasos:** se bloquea la zona *antes* de insertar para que dos copias
  del mismo evento que llegan a la vez queden serializadas; la segunda ve el conflicto
  y no hace nada.
- **`receivedAt`** = `clock.instant()` al entrar en el servicio.
- **`lastEventAt`** = `max(lastEventAt actual, occurredAt)`: los eventos pueden llegar
  desordenados y la spec pide el `occurredAt` *más reciente*, no el último recibido.
  Se calcula en `Occupancy.apply` (dominio, testeable).
- Nivel de aislamiento: `READ COMMITTED` (el de PostgreSQL por defecto). Con el bloqueo
  explícito es suficiente.

### 3.5 Dominio: `Occupancy`

Record puro, sin Spring:

```java
record Occupancy(int value, Instant lastEventAt) {
    OccupancyChange apply(EventType type, Instant occurredAt) { ... }
}
record OccupancyChange(Occupancy result, boolean anomaly) {}
```

- `ENTRY` → `value + 1`.
- `EXIT` con `value > 0` → `value - 1`.
- `EXIT` con `value == 0` → `value` se queda en 0, `anomaly = true`.
- No hay tope superior: la ocupación puede superar la capacidad (regla 6).

### 3.6 Validación (en el borde)

`EventRequest` (record) con Bean Validation:

| Campo | Anotaciones |
|-------|-------------|
| `eventId` | `@NotNull UUID` |
| `sensorId` | `@NotBlank @Size(max = 50)` |
| `zoneId` | `@NotBlank` |
| `type` | `@NotNull EventType` |
| `occurredAt` | `@NotNull @NotTooFarInFuture Instant` |

- **`@NotTooFarInFuture`**: restricción propia cuyo `ConstraintValidator` recibe por
  constructor el `Clock` y la tolerancia (Spring inyecta dependencias en los
  validadores). En tests unitarios se construye a mano con un `Clock.fixed`.
  Límite **inclusivo**: exactamente 5 min en el futuro se acepta; 5 min + 1 ms no.
  La tolerancia se configura en `application.yml`
  (`crowdpulse.events.max-future-skew: 5m`) con el valor que fija la spec.
  *Descartado:* comprobarlo a mano en el servicio: rompería la regla de validar en el
  borde y daría un formato de error distinto al del resto de campos.
- **`zoneId` inexistente → 422**, no se valida en el borde: requiere consultar la base
  de datos. Lo detecta el paso 1 del servicio (la propia consulta de bloqueo), sin
  consulta extra.
- **Formatos inválidos** (`type` desconocido, UUID o fecha mal formados, JSON roto):
  Jackson falla al deserializar antes de llegar a Bean Validation. El manejador de
  errores extrae el nombre del campo de la ruta de la excepción de Jackson para que el
  `ProblemDetail` lo nombre (CA3).
- `zoneId` distingue mayúsculas: `norte` no existe.
- Campos JSON desconocidos se ignoran (comportamiento por defecto de Spring Boot):
  un productor que envíe un campo nuevo no se rompe (principio 5).

### 3.7 Errores: `ProblemDetail`

`ApiExceptionHandler` (`@RestControllerAdvice`, extiende
`ResponseEntityExceptionHandler`):

| Situación | Estado | Extra en el cuerpo |
|-----------|--------|--------------------|
| Bean Validation falla | 400 | `errors: [{ field, message }]` |
| JSON ilegible / formato de campo inválido | 400 | `errors: [{ field, message }]` si se puede determinar el campo |
| `UnknownZoneException` en `POST` | 422 | `zoneId` |
| Zona inexistente en `GET` | 404 | `zoneId` |
| Cualquier otra excepción | 500 | solo `title` genérico; el detalle va al log |

- `detail` y `message` en español (texto de cara al usuario).
- Nunca se incluye la traza (`server.error.include-stacktrace: never`, y el manejador
  genérico no la copia).
- Cada rechazo se registra en el log con su `eventId` si se pudo leer (principio 9).

### 3.8 Consulta de ocupación

- `ZoneQueryRepository`: `SELECT` con `JOIN` de `zone` y `zone_occupancy`, ordenado
  por `zone_id`. Una consulta para todas, otra con `WHERE zone_id = ?`.
- `ZoneView` (record): `zoneId, name, capacity, occupancy, occupancyPct, lastEventAt`.
- `occupancyPct`: `BigDecimal` con 1 decimal y redondeo `HALF_UP`
  (12 345 / 20 000 → 61.725 → **61.7**). Se calcula en un método estático de
  `ZoneView`, probado sin Spring. *Descartado:* `double` y `Math.round(x * 10) / 10.0`:
  funciona casi siempre, pero el redondeo binario puede dar sorpresas en los bordes.
- `lastEventAt` se serializa como ISO-8601 UTC o `null`.
- Sin caché: cada `GET` lee de PostgreSQL (principio 6, CA8).

### 3.9 Configuración

- `application.properties` → `application.yml` (convención del proyecto).
- `ClockConfig` declara `Clock.systemUTC()`; ningún código llama a `Instant.now()`.
- Logs estructurados con el soporte de Spring Boot
  (`logging.structured.format.console: ecs`) y la API fluida de SLF4J
  (`log.atWarn().addKeyValue("eventId", ...)`). Sin dependencias nuevas.
- Conexión a PostgreSQL: en local la gestiona `spring-boot-docker-compose` (lee
  `compose.yaml`); al estar ahora en `../compose.yaml` hay que indicarlo con
  `spring.docker.compose.file`. Fuera de local, por variables de entorno
  (`SPRING_DATASOURCE_*`).
- Actuator: solo se expone `health`.

## 4. Módulo `simulator`

### 4.1 Java plano, sin Spring ni dependencias

Una aplicación de consola con `java.net.http.HttpClient`. El JSON del evento se
construye con un *text block* (5 campos con valores controlados: UUID, ids de zona,
enum, `Instant`; no hay nada que escapar). Se empaqueta como JAR ejecutable con
`maven-jar-plugin` (manifiesto con `Main-Class`), plugin estándar ya gestionado por
el padre.

Ejecución:
```
./mvnw -pl simulator package
java -jar simulator/target/simulator.jar --url=http://localhost:8080 \
     --rate=100 --duration=60s --zones=NORTE,SUR,ESTE,OESTE --duplicates=5
```

**Por qué:** el simulador hace de sensor externo; que no comparta código ni framework
con la API demuestra que el único acoplamiento es el contrato HTTP.
*Descartado:* Spring Boot + `RestClient`: daría Jackson y `@ConfigurationProperties`
gratis, pero arrastra un framework entero para un bucle que envía peticiones.

### 4.2 Diseño

| Clase | Responsabilidad |
|-------|-----------------|
| `SimulatorConfig` (record) | Parsea y valida los argumentos `--clave=valor` |
| `TrafficGenerator` | Decide el siguiente evento de una zona. Sin E/S; recibe `Random` (con semilla en tests) y el progreso de la simulación (0.0–1.0) |
| `EventSender` | Envía un evento con `HttpClient` y devuelve el código de estado |
| `SimulationReport` | Cuenta enviados, duplicados, respuestas por código y ocupación esperada por zona |
| `Main` | Arranca un hilo por zona, espera a que terminen e imprime el informe |

- **Perfil de llegada/salida:** la probabilidad de `ENTRY` baja linealmente de 0,9 al
  inicio a 0,1 al final. El generador lleva la ocupación simulada de su zona y, si es
  0, fuerza un `ENTRY`: nunca es negativa.
- **Un hilo por zona, envíos síncronos dentro de cada hilo.** Los eventos de una zona
  llegan a la API en el orden en que se generan, así que la API nunca ve un `EXIT`
  antes que su `ENTRY` y no se producen anomalías espurias. Eso hace que CA10 (suma de
  ocupaciones = entradas − salidas únicas) se cumpla de forma determinista. Imita el
  orden por zona que tendremos con Kafka. La tasa total se reparte entre zonas
  (100 ev/s con 4 zonas → 25 ev/s por hilo, 40 ms por envío; de sobra en local).
- **Ritmo:** cada hilo calcula el instante objetivo del evento *i*
  (`inicio + i / tasa`) y espera hasta él; si va retrasado, envía sin esperar.
  El informe muestra la tasa real conseguida.
- **Duplicados:** con probabilidad `--duplicates`% se reenvía un evento ya enviado de
  esa zona (mismo `eventId`). No cuenta para la ocupación esperada.
- **`occurredAt`** = instante del simulador (`Clock` inyectado) al generar el evento.
- **`sensorId`:** `{zoneId}-P01` … `{zoneId}-P05`, elegido al azar.
- **Informe final:** eventos enviados, duplicados enviados, respuestas por código, tasa
  real y ocupación esperada por zona y total, para compararla con
  `GET /api/v1/zones`.

## 5. `compose.yaml` ⚠️ (modifica el fichero)

Cambios propuestos:
- Imagen fijada a `postgres:18` en lugar de `latest` (principio 7: entorno
  reproducible). La misma versión en Testcontainers.
- Base de datos y usuario `crowdpulse`. La contraseña con valor por defecto solo para
  desarrollo: `${POSTGRES_PASSWORD:-crowdpulse}`.
- El puerto sigue siendo aleatorio en el host (lo resuelve `spring-boot-docker-compose`).

## 6. Estrategia de tests

`./mvnw test` ejecuta unitarios (Surefire, `*Test`); `./mvnw verify` añade integración
(Failsafe, `*IT`). Se configura `maven-failsafe-plugin` en el padre (ya versionado por
Spring Boot).

### 6.1 Unitarios (sin Spring)

| Test | Cubre |
|------|-------|
| `OccupancyTest` | ENTRY/EXIT, EXIT en 0 → anomalía (regla 5), por encima de capacidad (regla 6), `lastEventAt` con eventos desordenados |
| `ZoneViewTest` | `occupancyPct`: 61.725 → 61.7, 0 %, > 100 % |
| `NotTooFarInFutureValidatorTest` | CA5 con `Clock.fixed`: +4 min ✔, +5 min ✔, +5 min 1 ms ✘, pasado ✔ |
| `TrafficGeneratorTest` | Nunca negativa; más ENTRY al inicio y más EXIT al final; ratio de duplicados (semilla fija) |
| `SimulatorConfigTest` | Argumentos válidos, por defecto y erróneos |

### 6.2 Integración (Testcontainers, PostgreSQL real)

`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureRestTestClient`: las
peticiones pasan por HTTP real. Un `Clock` fijo se registra en una `@TestConfiguration`
con `@Primary`. Antes de cada test se vacía `sensor_event` y se pone la ocupación a 0
(`@Sql`), para que los tests sean independientes.

| Test | Criterio |
|------|----------|
| `EventIngestionIT` | CA1 (3 ENTRY + 1 EXIT → 2), CA2 (duplicado → dos 202, un cambio), CA3 (campo ausente, `type` inválido, UUID mal formado, JSON roto → 400 que nombra el campo y ocupación sin cambios), CA4 (422), CA5 a nivel HTTP, CA7 (EXIT en 0 → 202, ocupación 0, evento guardado con `anomaly = true`) |
| `ConcurrentIngestionIT` | CA6: 1 000 ENTRY distintos desde 20 hilos → exactamente 1 000; variante con el mismo evento enviado desde varios hilos → cuenta una vez |
| `ZoneQueryIT` | Listado de 4 zonas ordenado, formato de la respuesta, CA9 (404) |
| `RestartIT` | CA8: contenedor compartido (estático); se arranca la app, se envían eventos, se cierra el contexto, se arranca otro y la ocupación es la misma |
| `MigrationIT` | Las migraciones se aplican sobre una base vacía y crean las 4 zonas |

### 6.3 Verificación manual

- Tras cada cambio de API: `docker compose up -d`, `./mvnw -pl api spring-boot:run` y
  `curl` contra los tres endpoints y los casos de error.
- CA10: simulador a 100 ev/s durante 60 s con 5 % de duplicados; cero 5xx en el
  informe y ocupación esperada = suma de `GET /api/v1/zones`.
- CA11: `git clone` en un directorio limpio y `./mvnw verify` con Docker en marcha.

## 7. Trazabilidad spec → plan

| Spec | Dónde se resuelve |
|------|-------------------|
| Regla 1 | `Occupancy.apply` |
| Regla 2 | PK `event_id` + `ON CONFLICT DO NOTHING` (§3.4) |
| Regla 3 | `@Transactional` en `EventService.accept` |
| Regla 4 | `SELECT ... FOR UPDATE` por zona |
| Regla 5 | `Occupancy.apply` + columna `anomaly` + log WARN + `CHECK` |
| Regla 6 | Sin tope superior; `occupancyPct` puede superar 100 |
| Regla 7 | Tabla `sensor_event`, nunca se actualiza ni borra |
| CA1–CA11 | §6 |

## 8. ADRs propuestos

- `docs/adr/0001-jdbcclient-en-lugar-de-jpa.md` (§3.2).
- `docs/adr/0002-bloqueo-pesimista-por-zona.md` (§3.4).

## 9. Riesgos y puntos a revisar

1. **Incoherencias en la spec:** la cabecera dice `Fase: 2` (debería ser 1, según la
   constitución y `MEMORY.md`), y la regla 5 tiene erratas («0.Qué»,
   «guardamarcado»). Conviene corregirlas antes de aprobar este plan.
2. **`lastEventAt` y eventos anómalos:** el plan considera que un `EXIT` anómalo sí se
   «aplica» (actualiza `lastEventAt`) aunque la ocupación no cambie. La spec no lo
   dice explícitamente.
3. **Rendimiento del bloqueo:** el bloqueo por zona limita el rendimiento de una zona
   al de una transacción cada vez. Para 100 ev/s sobra; no está pensado para miles por
   segundo en una sola zona (eso lo aborda la fase 2).
4. **Duplicado con datos distintos** (mismo `eventId`, otra zona o tipo): se trata como
   duplicado si la zona existe; si la zona no existe, se responde 422 antes de detectar
   el duplicado. No se compara el contenido. Es un caso raro que la spec no cubre.
5. **Ruta de `compose.yaml`** desde el módulo `api`: verificar en la primera tarea que
   `spring.docker.compose.file: ../compose.yaml` funciona con `./mvnw -pl api
   spring-boot:run`.

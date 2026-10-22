# Plan 003 — Procesamiento asíncrono con Kafka

- **Estado:** Aprobado (2026-10-22)
- **Spec:** [`spec.md`](spec.md)
- **Fase:** 2
- **Fecha:** 2026-10-22

Cómo se implementa la spec 003 sobre lo que dejaron la 001 y la 002. Cada decisión no
obvia lleva la alternativa descartada. Las marcadas con ⚠️ necesitan tu aprobación
explícita (`CLAUDE.md` › Límites): **dos módulos nuevos, dependencias nuevas, Kafka en
`compose.yaml` y una migración nueva**.

---

## 1. Idea central

```
simulator ──POST──► api ──(valida, 422, receivedAt)──► Kafka ──► processor ──► PostgreSQL
                     │        crowdpulse.sensor-events            │  (reglas 001 y 002,
                     │        clave = zoneId, 3 particiones       │   processedAt)
                     │                                            └──► crowdpulse.sensor-events.dlt
                     └──GET /zones, /alerts ◄─────────── PostgreSQL
```

- **La API deja de escribir estado.** Valida (400), comprueba la zona (422, D2), añade
  `receivedAt`, publica y espera la confirmación de Kafka: 202 si llega, 503 si no (D3).
- **El `processor` hace exactamente lo que hoy hace `EventService.accept`**, con el mismo
  código: la misma transacción, el mismo `FOR UPDATE`, el mismo `ON CONFLICT DO NOTHING`.
  Lo único nuevo es de dónde viene el evento y que guarda `processedAt`.
- **Un mensaje = una transacción = una confirmación de offset**, en este orden:
  ```
  listener recibe el mensaje
    1. JSON → SensorEventMessage, comprobar versión y campos     (falla → DLT sin reintentos)
    2. EventProcessor.process(...)  [@Transactional]              (igual que EventService.accept)
       lock zona → Occupancy.apply → INSERT evento (o duplicado: fin) → AlertPolicy → UPDATE → alerta
    3. COMMIT de PostgreSQL
    4. el contenedor confirma el offset (AckMode RECORD)
  ```
  Si el proceso muere entre 3 y 4, el mensaje se vuelve a entregar y el paso 2 lo
  descarta como duplicado (regla 4, CA7). Si muere antes de 3, no se guardó nada y se
  vuelve a procesar entero (CA6).

*Descartado:* que la API guarde el evento en PostgreSQL y un proceso aparte lo publique
(patrón *outbox*). Es la forma correcta de no depender de Kafka para responder, pero
añade una tabla, un *poller* y otro sitio donde perder o duplicar mensajes. La spec ya
eligió responder 503 cuando Kafka no confirma (D3).

## 2. Estructura del repositorio ⚠️ (dos módulos nuevos)

```
crowdpulse/
├── pom.xml          # + <module>shared</module>, <module>processor</module>
├── shared/          # jar sin Spring Boot: contrato del mensaje, enums y migraciones
├── api/             # ingesta y consultas; ya no procesa
├── processor/       # Spring Boot: consumidor de Kafka, reglas de las specs 001 y 002
└── simulator/
```

### `shared` (nuevo) — lo que las dos aplicaciones tienen que ver igual
| Contenido | Por qué está aquí |
|-----------|-------------------|
| `db/migration/V1…V5` (movidas desde `api` con `git mv`) | Las dos aplicaciones usan el mismo esquema; sus tests lo necesitan |
| `EventType`, `AlertLevel` | Los usan la API (validar, mostrar) y el `processor` (aplicar) |
| `SensorEventMessage` (record, versión 1) y `Topics` (nombres de los topics y de la cabecera de versión) | El contrato API → `processor`: si cada lado tuviera su copia, podrían divergir sin que fallara ningún test |
| `Percentages.of(ocupación, capacidad)` (hoy `ZoneView.percentage`) | La API lo muestra y el `processor` lo escribe en el log de cambio de nivel |

- **Mover las migraciones no es editarlas:** Flyway compara el contenido (*checksum*),
  no la ruta, y la ubicación por defecto `classpath:db/migration` sigue valiendo. Una BD
  local de la fase 1 valida sin cambios.
- **Las dos aplicaciones ejecutan `migrate` al arrancar.** Flyway toma un bloqueo en
  PostgreSQL mientras migra, así que si arrancan a la vez una espera a la otra, y no hay
  que arrancarlas en un orden concreto. *Descartado:* que solo migre una. La otra fallaría
  si arranca primero con la BD vacía.

*Descartado:* sin `shared`, migraciones en `api` y el `processor` leyéndolas con
`filesystem:../api/...` en sus tests. Funciona, pero depende de la carpeta desde la que
se ejecute Maven y deja el contrato del mensaje duplicado. Tampoco un `processor` que
dependa del jar de `api`: son dos aplicaciones Spring Boot y arrastraría la web entera.

### Paquetes
| Módulo | Paquete raíz | Por funcionalidad |
|--------|--------------|-------------------|
| `shared` | `com.alexroyodev.crowdpulse.shared` | plano, son 6 clases |
| `api` | `com.alexroyodev.crowdpulse` (igual que hoy) | `event`, `zone`, `alert`, `common` |
| `processor` | `com.alexroyodev.crowdpulse.processor` | `event`, `occupancy`, `alert`, `common` |

### Qué se mueve de `api` a `processor` (con `git mv`, para conservar el historial)
`EventService` (→ `EventProcessor`), `SensorEvent`, `SensorEventRepository`, todo
`occupancy` (`Occupancy`, `OccupancyChange`, `LockedZone`, `OccupancyRepository`),
`AlertPolicy`, `AlertProperties`, `AlertConfig`, `Alert` y el `insert` de
`AlertRepository`. La configuración `crowdpulse.alerts` pasa al `application.yml` del
`processor`. En `api` se quedan los controladores, las vistas y las consultas.

## 3. Dependencias ⚠️

| Módulo | Añade | Para |
|--------|-------|------|
| `api` | `spring-boot-starter-kafka` | `KafkaTemplate`, `NewTopic` |
| `api`, `processor` | `spring-boot-starter-kafka-test`, `org.testcontainers:testcontainers-kafka` (test) | Kafka real en los `*IT` (`KafkaContainer` con `@ServiceConnection`) |
| `processor` | `spring-boot-starter-kafka`, `-jdbc`, `-flyway`, `-validation`, `-actuator`, `-webmvc`, `spring-boot-docker-compose`, `postgresql`, `flyway-database-postgresql` y sus `-test` | Las mismas que `api` hoy, más Kafka. `webmvc` solo para exponer `/actuator/health` (principio 9) |
| `api`, `processor` | `shared` | §2 |
| `shared` | Ninguna en `compile`; Jackson y JUnit solo en `test` (para `SensorEventMessageTest`) | Que siga siendo un jar casi vacío. Jackson 3 convierte records e `Instant` en ISO-8601 sin anotaciones |

Versiones gestionadas por `spring-boot-starter-parent` 4.1.1 (Spring Kafka 4.x, cliente de
Kafka 4.x). Comprobado en el repositorio Maven local: `spring-boot-kafka` 4.1.1 trae la
conexión automática para el `KafkaContainer` de Testcontainers, **pero no para Docker
Compose** (ver §6).

## 4. Contrato del mensaje (versión 1)

```
topic:    crowdpulse.sensor-events          (3 particiones, réplica 1)
clave:    "NORTE"                            (zoneId, String)
cabecera: crowdpulse-message-version = "1"
valor:    {"eventId":"0b6c…","sensorId":"S-NORTE-01","zoneId":"NORTE","type":"ENTRY",
           "occurredAt":"2026-10-22T18:00:00Z","receivedAt":"2026-10-22T18:00:00.123Z"}
```

- **JSON como texto, convertido a mano con el `JsonMapper` de Spring Boot** (Jackson 3):
  la API serializa el record a `String` y publica con `StringSerializer`; el `processor`
  recibe `String` y lo convierte dentro del listener. Un JSON roto es una excepción más
  del listener: va al DLT por el camino normal (§5.3), con los bytes originales.
  *Descartado:* el `JacksonJsonSerializer`/`Deserializer` de Spring Kafka. Un mensaje que
  no se puede deserializar falla **antes** del listener, y para que no bloquee la
  partición hace falta envolverlo en un `ErrorHandlingDeserializer`; además, por defecto
  añade cabeceras con el nombre de la clase Java. Más piezas que explicar para lo mismo.
- **La versión va en una cabecera, no en el JSON:** el valor es «el evento tal como llegó
  más `receivedAt`», como dice la spec. Un mensaje sin cabecera o con otra versión no se
  procesa: va al DLT. Así, un cambio incompatible futuro (versión 2) nunca se interpreta
  mal en silencio.
- **Test de contrato** en `shared` (`SensorEventMessageTest`, unitario): el JSON de arriba,
  literal, se convierte en el record y vuelta, campo a campo.
- **Por qué 3 particiones:** con 5 zonas, varias comparten partición. Eso demuestra que
  el orden depende de la clave y no de tener una partición por zona (CA5).

## 5. Diseño por módulo

### 5.1 `api`
| Clase | Cambio |
|-------|--------|
| `EventController` | Igual: `@Valid`, 202. Llama a `EventPublisher` en lugar de `EventService` |
| `EventPublisher` (nuevo, `event`) | `zones.exists(zoneId)` → si no, `UnknownZoneException` (422, el log WARN de hoy); construye el mensaje con `receivedAt = clock.instant()`; `kafkaTemplate.send(record).get()`; cualquier fallo → `EventNotPublishedException` |
| `ZoneQueryRepository` | `exists(zoneId)`: `SELECT EXISTS (SELECT 1 FROM zone WHERE zone_id = :zoneId)` |
| `ApiExceptionHandler` | `EventNotPublishedException` → **503** `ProblemDetail` («No se ha podido registrar el evento; reinténtalo.»), cabecera `Retry-After: 1`, log WARN con `eventId` y la causa |
| `KafkaTopicsConfig` (nuevo, `common`) | `NewTopic` para `crowdpulse.sensor-events` (3 particiones) |
| `AlertRepository` | Solo la consulta del histórico; el `insert` se va al `processor` |

**Cuándo se rinde la API (D3).** El productor espera como mucho lo que digan sus propios
límites, y `get()` termina siempre dentro de ellos:
```yaml
spring.kafka.producer:
  acks: all                       # 202 solo cuando el líder y sus réplicas lo tienen
  properties:
    max.block.ms: 3000            # esperar metadatos o hueco en el buffer
    request.timeout.ms: 2000
    delivery.timeout.ms: 3000     # tope total de un envío, reintentos incluidos
```
Con Kafka parado, el sensor recibe 503 en unos 3 s. *Descartado:* `send()` sin esperar
y responder 202 enseguida. Rompería la regla 3: un 202 de un evento que Kafka nunca tuvo.

- **El 422 cuesta una consulta por evento** (D2). Son 5 filas de catálogo y una clave
  primaria; a 100 ev/s no se nota. *Descartado:* una caché del catálogo. Habría que
  explicar cuándo se invalida para ahorrar algo que no es un problema.
- **Carrera aceptada:** la API comprueba la zona y el `processor` la vuelve a comprobar.
  Hoy las zonas solo se crean con migraciones y nunca se borran, así que no pasa; si
  pasara, el mensaje iría al DLT (§5.3), no se perdería.

### 5.2 `processor`
| Clase | Qué es |
|-------|--------|
| `ProcessorApplication` | `main` |
| `SensorEventListener` (`event`) | `@KafkaListener(id = "sensor-events", topics = …)`. Comprueba la cabecera de versión, convierte el JSON, valida el record con Bean Validation (es el borde del `processor`) y llama a `EventProcessor` |
| `EventProcessor` (`event`) | Hoy `EventService.accept`, con dos cambios: recibe `SensorEventMessage` (con `receivedAt` ya puesto) y guarda `processedAt = clock.instant()` |
| `KafkaConfig` (`common`) | Error handler y DLT (§5.3), `NewTopic` del DLT |
| `ClockConfig` (`common`) | El de hoy |

```yaml
server.port: 8081                       # la API está en el 8080
spring.kafka.consumer:
  group-id: crowdpulse-processor
  auto-offset-reset: earliest           # la primera vez, desde el principio del topic
  # enable-auto-commit: false ya es el valor de Spring Boot: confirma el contenedor, no el cliente
spring.kafka.listener:
  ack-mode: record                      # confirmar cada mensaje después de su transacción
```
- **`auto-offset-reset: earliest`** es parte de la garantía: con `latest` (el valor por
  defecto de Kafka), los eventos publicados antes de que el grupo arranque por primera vez
  se saltarían.
- **`ack-mode: record`** confirma un offset por mensaje, justo después de que la
  transacción haga commit. *Descartado:* `batch` (el valor por defecto). También es
  correcto (confirma al acabar todo lo recibido en un `poll`), pero tras un reinicio
  repite más mensajes y la explicación «un mensaje, una transacción, un offset» se pierde.
  A 100 ev/s el coste de confirmar cada mensaje no importa.
- **`processedAt` y `detected_at`:** el registro de `alert` guarda como `detected_at` el
  `processedAt`, porque ahora el cambio de nivel se detecta al procesar, no al recibir.
- **Un solo hilo consumidor** (`concurrency` por defecto, 1) para las 3 particiones.
  Basta para 100 ev/s. El `FOR UPDATE` se queda: protege el caso de dos consumidores que
  se solapen durante un reparto de particiones, y lo sigue demostrando su test (§7).
- **Logs:** `DEBUG "Event processed"` con `eventId`, `zoneId` y `latencyMs`
  (`processedAt − receivedAt`); los WARN de duplicado, anomalía y cambio de nivel, iguales
  que hoy.

### 5.3 Reintentos y DLT (regla 7, D1)
```java
var recoverer = new DeadLetterPublishingRecoverer(template,
        (record, ex) -> new TopicPartition(Topics.SENSOR_EVENTS_DLT, -1));  // partición: la elige Kafka por la clave
var handler = new DefaultErrorHandler(loggingRecoverer(recoverer), new FixedBackOff(1000L, 2L)); // 3 intentos
handler.addNotRetryableExceptions(JacksonException.class, ConstraintViolationException.class,
        UnsupportedMessageVersionException.class, UnknownZoneException.class);
```
- **Dos tipos de fallo.** Los que no se arreglan reintentando (JSON roto, campos que
  faltan, versión desconocida, zona inexistente) van **directos** al DLT. El resto (p. ej.
  un error puntual de PostgreSQL) se reintenta 2 veces con 1 s de espera, y luego al DLT.
- `loggingRecoverer` escribe `ERROR "Event sent to dead-letter topic"` con `eventId` (si
  se pudo leer), `zoneId` (la clave), partición, offset y la excepción, y después delega
  en el `DeadLetterPublishingRecoverer`. Spring Kafka añade al mensaje del DLT cabeceras
  con el topic, la partición, el offset y la excepción originales.
- Después de mandarlo al DLT, el contenedor confirma el offset y sigue con el siguiente
  mensaje de la partición (CA3b). Si publicar en el DLT también falla, el mensaje no se
  confirma y se vuelve a intentar: nunca se descarta.
- DLT con 1 partición: nadie lo consume en esta fase, solo se inspecciona.
- *Descartado:* los *retry topics* de Spring Kafka (`@RetryableTopic`), que reintentan en
  topics aparte sin bloquear la partición. Rompen el orden por zona: el evento que se
  reintenta se adelanta o se retrasa respecto a los siguientes de su zona.

## 6. Esquema y entorno

### Migración ⚠️ `V5__add_processed_at.sql` (en `shared`; V1–V4 no se tocan)
```sql
-- Spec 003: when the processor applied the event. Latency = processed_at - received_at.
ALTER TABLE sensor_event ADD COLUMN processed_at TIMESTAMPTZ NULL;
-- Phase 1 processed every event in the request that received it.
UPDATE sensor_event SET processed_at = received_at;
ALTER TABLE sensor_event ALTER COLUMN processed_at SET NOT NULL;
```
- Las filas antiguas reciben `received_at`, porque en la fase 1 se procesaban en la misma
  petición: es el dato real, no un relleno.
- **Sin `CHECK (processed_at >= received_at)`:** los dos instantes vienen de relojes de
  procesos distintos. Si un día se desfasan unos milisegundos, la restricción mandaría
  eventos correctos al DLT. CA1 lo comprueba un test (mismo equipo, mismo reloj).

### `compose.yaml` ⚠️
```yaml
  kafka:
    image: 'apache/kafka:<versión fijada en la tarea>'   # la misma que en Testcontainers
    ports:
      - '9092:9092'
```
- **Puerto fijo, a diferencia de PostgreSQL.** Spring Boot 4.1 no reconoce Kafka en
  Docker Compose (solo en Testcontainers), así que no puede descubrir un puerto aleatorio.
  `localhost:9092` es justo el valor por defecto de `spring.kafka.bootstrap-servers` y lo
  que anuncia la imagen `apache/kafka` en modo de un solo nodo (KRaft, sin ZooKeeper).
  Contrapartida: falla si otro Kafka usa el 9092 en tu equipo.
- **El `processor` usa `spring.docker.compose.lifecycle-management: start-only`.** Si las
  dos aplicaciones usaran el valor por defecto, parar una podría parar los contenedores
  de la otra. Se verifica en la tarea; lo recomendado sigue siendo `docker compose up -d`
  antes de arrancar nada.
- Comandos nuevos para `CLAUDE.md` y el README:
  `./mvnw -pl processor spring-boot:run` (puerto 8081, `curl localhost:8081/actuator/health`).

## 7. Tests

Regla: **ninguna aserción de hoy desaparece; cambia de sitio** cuando lo que prueba se
mueve al `processor`. Las de procesamiento pasan a llamar a `EventProcessor` directamente
(síncrono y determinista, como hoy el `EventService` detrás del POST). Lo asíncrono se
prueba aparte, con Kafka real.

### Dónde va cada test existente
| Hoy (`api`) | Pasa a | Qué cambia |
|-------------|--------|------------|
| `OccupancyTest`, `AlertPolicyTest`, `AlertPropertiesTest` | `processor` | Nada (paquete) |
| `EventIngestionIT`: validación (400), 422, `occurredAt` futuro, campos desconocidos | se queda en `api` | Además: comprueba que **no** hay mensaje en el topic (CA3) |
| `EventIngestionIT`: entradas/salidas, duplicado, `EXIT` en 0, tiempos | `processor` › `EventProcessingIT` | Llama a `EventProcessor`; los tiempos incluyen `processedAt` |
| `ConcurrentIngestionIT`, `TransactionAtomicityIT`, `AlertAtomicityIT`, `ConcurrentAlertIT` | `processor` | Llaman a `EventProcessor`; mismas aserciones |
| `AlertIT`: cruces, histéresis, oscilación, reenvío, anomalía, log | `processor` › `AlertProcessingIT` | Llama a `EventProcessor` |
| `AlertIT`: histórico, filtros, `limit`, 404, 400 | se queda en `api` | El estado se prepara con SQL, como hoy |
| `CrowdpulseApplicationIT` (umbrales de la spec 002) | `processor` | — |
| `RestartIT` | `processor` › `ProcessorRestartIT` | Reinicia el `processor` de verdad (dos arranques con `SpringApplicationBuilder`, como hoy) |
| `MigrationIT`, `InitialAlertLevelMigrationIT`, `ZoneQueryIT`, `ApiErrorsIT`, `NotTooFarInFutureValidatorTest`, `ZoneViewTest`, `ApiExceptionHandlerTest` | se quedan en `api` | `MigrationIT` añade V5 |

`TestcontainersConfiguration` (PostgreSQL + `KafkaContainer`, compartidos por todos los
`*IT` de su módulo) y `reset-state.sql` existen en los dos módulos. Los tests de Kafka
usan un `eventId` y una zona propios, o vacían el estado con `@Sql` y esperan a que el
`processor` se quede sin mensajes pendientes, para no ver mensajes de otro test.

### Nuevos
| Test | Módulo | Criterio |
|------|--------|----------|
| `SensorEventMessageTest` (unitario) | `shared` | Contrato §4 |
| `EventPublishingIT` | `api` | 202 → un mensaje en el topic con clave `zoneId`, cabecera de versión y `receivedAt` (CA1, parte API); 400/422 → ningún mensaje (CA3); 20 eventos de una zona → todos en la misma partición y en orden (CA5, parte API) |
| `KafkaOutageIT` | `api` | CA2: con su **propio** `KafkaContainer`, se envían eventos (202), se pausa el contenedor (`docker pause`, conserva el puerto) → 503 en ≤ ~3 s con `ProblemDetail`; se reanuda → los eventos con 202 están en el topic y un reintento del que dio 503 recibe 202 |
| `KafkaProcessingIT` | `processor` | CA1 (publicar → en < 5 s ocupación cambiada, `receivedAt ≤ processedAt`); CA4 (mismo mensaje dos veces → +1); CA5 (`ENTRY`/`EXIT` alternos en una zona vacía: cualquier desorden produciría una anomalía; al final 0 y ninguna anomalía); CA8 (1 000 `ENTRY` publicados desde 20 hilos → 1 000; cruce de umbral y anomalía por el topic) |
| `DeadLetterIT` | `processor` | CA3b: JSON roto, versión desconocida, campo que falta y zona inexistente → en el DLT a la primera, con log `ERROR`; el siguiente evento de la misma zona sí se procesa. Un fallo transitorio (espía que falla 1 vez) se reintenta y se procesa, sin DLT |
| `AtLeastOnceIT` | `processor` | CA7: un espía en `EventProcessor` deja hacer el commit y **después** lanza una excepción (como si el proceso muriera antes de confirmar el offset) → se vuelve a entregar, cuenta una vez. Y el caso contrario: falla **antes** del commit → se vuelve a entregar y cuenta (no se pierde) |
| `ProcessorRestartIT` | `processor` | CA6: 1 000 eventos publicados; se para el listener (`KafkaListenerEndpointRegistry`) a mitad y se arranca otra vez → ocupación exacta. Además, lo de `RestartIT`: estado y alertas iguales tras reiniciar la aplicación entera |

**Esperas en los tests asíncronos:** un bucle propio en la clase de utilidades de test
(como `Concurrently`), que consulta cada 100 ms hasta que se cumple la condición o pasan
10 s. *Descartado:* Awaitility, para no añadir otra dependencia por un bucle de 10 líneas.

### Pruebas de mutación (principio 2), una vez cada una
| Se quita | Debe fallar |
|----------|-------------|
| Confirmar el offset **antes** de procesar (`ack-mode: manual_immediate` y `ack()` al principio) | `AtLeastOnceIT` (fallo antes del commit: el evento se pierde) |
| Ignorar el resultado de `insertIfAbsent` (no detectar duplicados) | `AtLeastOnceIT` (fallo después del commit), `KafkaProcessingIT` CA4 |
| Clave = `eventId` en vez de `zoneId` | `EventPublishingIT` CA5 |
| Sin `DeadLetterPublishingRecoverer` (el handler por defecto solo registra y sigue) | `DeadLetterIT` |
| Responder 202 sin esperar a `get()` | `KafkaOutageIT` |
| `FOR UPDATE`, `@Transactional` (como en las specs 001 y 002) | `ConcurrentIngestionIT`, `TransactionAtomicityIT`, `AlertAtomicityIT`, `ConcurrentAlertIT` |

### Manual
- **CA9:** `docker compose up -d`, API y `processor` en marcha, simulador a 100 ev/s
  60 s con 5 % de duplicados → OK. La latencia queda en `sensor_event.processed_at`; el
  README trae la consulta (`percentile_cont(0.5 / 0.95 / 0.99)` de
  `processed_at − received_at`). Sin endpoint nuevo: las métricas son de la fase 4.
- Con `curl`: 202, 400, 422 y 503 (parando el contenedor de Kafka).
- **CA10:** `./mvnw verify` desde un clon limpio.

## 8. Simulador (D4)
- Al terminar de enviar, `Main` consulta `GET /api/v1/zones` cada 500 ms hasta que el
  cambio de cada zona coincide con el esperado o pasan 30 s, y usa la última lectura
  para el informe.
- `SimulationReport` añade una línea: «Ocupación cuadrada en 1,2 s» o «No cuadró en 30 s».
- La espera se prueba sin red (`ZoneRunnerTest` ya inyecta el envío; aquí se inyecta la
  consulta y un reloj).
- **Sin reintentos de 503** en el simulador: la spec no los pide. Un 503 cuenta como
  respuesta distinta de 202 y la demo sale en error, que es lo que debe pasar si Kafka
  falla durante la demo.

## 9. ADR propuesto
- `docs/adr/0004-kafka-entre-api-y-processor.md`: clave `zoneId`, offset confirmado
  después del commit (`ack-mode: record`), idempotencia por `eventId` en el consumidor,
  503 en lugar de *outbox*, DLT sin *retry topics*. Es la decisión que más se pregunta en
  una entrevista sobre este proyecto.

## 10. Riesgos y puntos a revisar
1. **PostgreSQL caído más de ~3 s manda eventos al DLT.** Es lo que dice la regla 7 (unos
   pocos reintentos y al DLT), pero esos eventos no se aplican hasta que alguien los
   reprocese, y la ocupación queda mal mientras tanto. Distinguir «BD caída» (esperar sin
   límite) de «mensaje malo» (DLT) es justo un escenario de caída de la fase 3. ¿Lo
   dejamos así y lo anoto como problema abierto?
2. **CA2 se demuestra en dos mitades:** la API prueba que con Kafka pausado responde 503
   y que lo que recibió 202 está en el topic; el `processor` prueba que todo lo que está
   en el topic se procesa. No hay un test automático con las dos aplicaciones a la vez
   (necesitaría un tercer módulo de tests); de extremo a extremo lo cubre la demo de CA9.
3. **CA8 de 1 000 concurrentes por el topic** no estresa el `FOR UPDATE` (hay un solo hilo
   consumidor). Lo sigue demostrando `ConcurrentIngestionIT` contra `EventProcessor`.
4. **Puerto 9092 fijo** en local (§6).
5. **Consistencia eventual visible:** justo después de un 202, `GET /zones` puede no
   reflejar aún el evento. Está en la spec; cambia lo que ve quien pruebe con `curl`.
6. **Volumen del cambio:** casi todo es mover código y tests, pero son muchos ficheros.
   Orden previsto de las tareas, cada una con `./mvnw verify` en verde:
   `shared` + migraciones + V5 → el corte: el código y los tests de procesamiento se
   mueven al `processor` y la API pasa a publicar en Kafka (tiene que ser a la vez: sin
   `EventService`, el `POST` no tendría nada a lo que llamar) → listener y DLT → reinicios
   → simulador → docs. **Entre el corte y el listener no hay camino de extremo a extremo:**
   los tests de cada módulo siguen en verde, pero la demo del simulador no funciona hasta
   que el `processor` consume.

# Tareas 003 — Procesamiento asíncrono con Kafka

- **Estado:** Aprobado (2026-10-22)
- **Spec:** [`spec.md`](spec.md) · **Plan:** [`plan.md`](plan.md)

Una tarea cada vez. Está terminada cuando su verificación pasa, `./mvnw verify` está en
verde y `MEMORY.md` está actualizado. Al terminar se marca `[x]`. Cuando una tarea mueve
tests, ninguna aserción se borra ni se relaja: cambia de sitio (plan §7).

---

## Bloque A — Módulo `shared`

- [ ] **T01 — Módulo `shared` con las migraciones y los tipos compartidos** (plan §2)
  - `shared/pom.xml` (jar, sin Spring Boot) y `<module>shared</module>` en el `pom.xml` raíz,
    antes de `api`.
  - `git mv` de `api/src/main/resources/db/migration` a `shared/src/main/resources/db/migration`.
  - `git mv` de `EventType` y `AlertLevel` a `com.alexroyodev.crowdpulse.shared`;
    `ZoneView.percentage` → `shared.Percentages.of`. `api` depende de `shared`.
  - Nada más cambia: `api` sigue procesando de forma síncrona.
  - **Verificación:** `./mvnw verify` en verde con los mismos tests que antes; con la BD
    local de la fase 1 (sin `down -v`), la API arranca y Flyway valida V1–V4 sin errores
    (los *checksums* no cambian al mover los ficheros).

- [ ] **T02 — Migración V5 y contrato del mensaje** (plan §4, §6)
  - `V5__add_processed_at.sql`. Mientras la API siga procesando, `SensorEventRepository`
    guarda `processed_at = received_at` (lo que significa en la fase 1).
  - `shared.SensorEventMessage` (record v1) y `shared.Topics` (topics y cabecera de versión).
  - `SensorEventMessageTest`: el JSON literal del plan §4 ↔ record, campo a campo.
  - `MigrationIT`: V5 aplicada, `processed_at` `NOT NULL`; una fila previa a V5 recibe su
    `received_at` (con una base migrada hasta V4, como `InitialAlertLevelMigrationIT`).
  - **Verificación:** `./mvnw verify`; `psql`: columna nueva y V5 en `flyway_schema_history`.

## Bloque B — El corte: API → Kafka, procesamiento → `processor`

- [ ] **T03 — Kafka en `compose.yaml` y en los tests de la API** (plan §3, §6)
  - Servicio `kafka` (`apache/kafka`, versión fijada, `9092:9092`).
  - Dependencias de Kafka en `api`; `TestcontainersConfiguration` con `KafkaContainer`
    (misma imagen y versión).
  - `CrowdpulseApplicationIT` sigue arrancando con Kafka en el contexto.
  - **Verificación:** `docker compose up -d` levanta los dos servicios; `./mvnw verify`.

- [ ] **T04 — Módulo `processor` con el código movido** (plan §2, §5.2, §7)
  - `processor/pom.xml`, `ProcessorApplication`, `application.yml` (puerto 8081,
    `crowdpulse.alerts`, `start-only` de Docker Compose), `ClockConfig`.
  - `git mv` de `EventService` (→ `EventProcessor`, recibe `SensorEventMessage`, guarda
    `processedAt`), `SensorEvent`, `SensorEventRepository`, `occupancy/*`, `AlertPolicy`,
    `AlertProperties`, `AlertConfig`, `Alert` e `AlertRepository.insert`. `detected_at =
    processedAt`.
  - Tests movidos según la tabla del plan §7: `OccupancyTest`, `AlertPolicyTest`,
    `AlertPropertiesTest`, `EventProcessingIT`, `ConcurrentIngestionIT`,
    `TransactionAtomicityIT`, `AlertProcessingIT`, `AlertAtomicityIT`, `ConcurrentAlertIT`,
    `CrowdpulseApplicationIT` (umbrales). Llaman a `EventProcessor` directamente.
  - **Hecho junto con T05** (un solo commit): sin `EventService`, el `POST` de la API no
    compila hasta que publica.
  - **Verificación:** con T05.

- [ ] **T05 — La API publica en Kafka** (plan §5.1)
  - `EventPublisher`, `ZoneQueryRepository.exists`, `KafkaTopicsConfig`, configuración
    del productor (`acks: all` y los tres tiempos), `EventNotPublishedException` → 503 con
    `Retry-After` y su texto en `messages.properties`; `AlertRepository` solo consulta.
  - `EventIngestionIT` (lo que se queda) y `EventPublishingIT`: CA1 (parte API), CA3, CA5
    (parte API).
  - **Mutación:** clave = `eventId` → falla CA5 de `EventPublishingIT`; restaurar.
  - **Verificación:** `./mvnw verify` (los dos módulos); con `curl` contra compose:
    202 y el mensaje visible con `kafka-console-consumer.sh` dentro del contenedor
    (clave, cabecera y JSON); 400 y 422 sin mensaje.
  - **Desde aquí y hasta T07 no hay camino de extremo a extremo** (plan §10.6).

- [ ] **T06 — 503 cuando Kafka no confirma** (CA2, D3)
  - `KafkaOutageIT` con su propio `KafkaContainer`: 202 → `docker pause` → 503 en ≤ ~3 s
    con `ProblemDetail` → `unpause` → lo que recibió 202 está en el topic y el reintento
    recibe 202.
  - **Mutación:** responder 202 sin esperar a `get()` → falla; restaurar.
  - **Verificación:** `./mvnw verify`; manual: `docker compose stop kafka` → `curl` da 503.

## Bloque C — Consumo

- [ ] **T07 — Listener del `processor`** (plan §5.2; CA1, CA4, CA5, CA8)
  - `SensorEventListener` (versión, JSON, Bean Validation), `ack-mode: record`,
    `auto-offset-reset: earliest`, `group-id`; log `DEBUG "Event processed"` con
    `latencyMs`.
  - Utilidad de espera en los tests (cada 100 ms, máximo 10 s).
  - `KafkaProcessingIT`: CA1, CA4, CA5 (`ENTRY`/`EXIT` alternos sin anomalías), CA8
    (1 000 desde 20 hilos; cruce de umbral; anomalía).
  - **Verificación:** `./mvnw verify`; manual: API + `processor` en marcha, `curl` de un
    `ENTRY` y `GET /zones` lo refleja; el simulador corto (`--rate=10 --duration=5s`)
    vuelve a acabar en OK (puede necesitar T10 para no comprobar antes de tiempo).

- [ ] **T08 — Reintentos y DLT** (plan §5.3; CA3b)
  - `KafkaConfig`: `DefaultErrorHandler` (3 intentos, 1 s), excepciones no reintentables,
    `loggingRecoverer` (`ERROR` con `eventId`), `DeadLetterPublishingRecoverer` al topic
    `.dlt` (1 partición, `NewTopic`).
  - `DeadLetterIT`: JSON roto, sin versión, campo que falta, zona inexistente → DLT a la
    primera con log `ERROR`; el siguiente de la misma zona se procesa; un fallo transitorio
    se reintenta y no llega al DLT.
  - **Mutación:** sin el recoverer → falla; restaurar.
  - **Verificación:** `./mvnw verify`; manual: publicar un JSON roto con
    `kafka-console-producer.sh` y verlo en el DLT y en el log.

## Bloque D — Garantías de entrega

- [ ] **T09 — At-least-once y reinicio del `processor`** (CA6, CA7)
  - `AtLeastOnceIT`: fallo después del commit → cuenta una vez; fallo antes del commit →
    se reentrega y cuenta.
  - `ProcessorRestartIT`: 1 000 eventos, parar y arrancar el listener a mitad → exacto;
    estado y alertas iguales tras reiniciar la aplicación entera (lo que hacía `RestartIT`,
    que se elimina de `api` al pasar aquí).
  - **Mutaciones:** `ack()` antes de procesar → falla el fallo-antes-del-commit; ignorar
    el resultado de `insertIfAbsent` → falla el fallo-después-del-commit y CA4; restaurar.
  - **Verificación:** `./mvnw verify`, 3 veces seguidas en verde.

## Bloque E — Simulador y cierre

- [ ] **T10 — El simulador espera a que cuadre** (plan §8; D4)
  - Consulta cada 500 ms hasta que cuadra o pasan 30 s; línea nueva en el informe.
  - Tests unitarios con la consulta y el reloj inyectados: cuadra a la primera, cuadra
    tarde, no cuadra en 30 s.
  - **Verificación:** `./mvnw -pl simulator verify`.

- [ ] **T11 — Demo, documentación y verificación final** (CA9, CA10)
  - Demo: `--rate=100 --duration=60s --duplicates=5` → OK; latencia p50/p95/p99 con la
    consulta SQL del README.
  - `docs/adr/0004-kafka-entre-api-y-processor.md`.
  - `README.md` (diagrama, `processor`, garantías y sus tests, latencia) y `CLAUDE.md`
    (comandos del `processor`, puerto 9092).
  - CA10: `git clone` limpio + `docker compose up -d` + `./mvnw verify`.
  - Tabla de verificación de los CA al final de este fichero; spec y tareas terminadas;
    `MEMORY.md` actualizado (fase 2 cerrada).
  - **Verificación:** todos los CA con test o verificación manual registrada.

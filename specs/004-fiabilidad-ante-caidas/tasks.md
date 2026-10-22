# Tareas 004 — Fiabilidad ante caídas

- **Estado:** Aprobado (2026-10-22)
- **Spec:** [`spec.md`](spec.md) · **Plan:** [`plan.md`](plan.md)

Una tarea cada vez. Está terminada cuando su verificación pasa, `./mvnw verify` está en
verde (uno cada vez, con compose parado si falta memoria) y `MEMORY.md` está actualizado.
Al terminar se marca `[x]`. `DeadLetterIT` debe seguir en verde en todas (CA6).

---

## Bloque A — Base

- [x] **T01 — `DatabaseUnavailable` en `shared`** (plan §2.1, §8.1)
  - `shared/pom.xml`: `spring-tx` y `spring-jdbc` (versión de Spring Boot).
  - `shared.DatabaseUnavailable.isCause(Throwable)`: recorre la cadena de causas buscando
    las cuatro excepciones del plan §2.1.
  - `DatabaseUnavailableTest`: cada una, sola y envuelta en dos niveles;
    `DataIntegrityViolationException`, una `RuntimeException` cualquiera y `null` → `false`.
  - **Verificación:** `./mvnw test`.

- [x] **T02 — Tiempos de espera de la base de datos** (plan §2.3)
  - `application.yml` de `api` y `processor`: `hikari.connection-timeout: 3000` y
    `data-source-properties.socketTimeout: 10`.
  - **Verificación:** `./mvnw verify`; los contextos arrancan con la configuración nueva.

## Bloque B — `processor`

- [x] **T03 — Esperar a la base de datos sin límite** (plan §2.2, §2.4; CA1, CA2)
  - `KafkaConfig`: `setBackOffFunction` (D1), `RetryListener` → `DatabaseOutage.failed()`,
    `setLogLevel(DEBUG)`.
  - `DatabaseOutage` (`common`): `WARN` al caer y al volver, con la duración.
    `SensorEventListener` llama a `succeeded()` tras cada evento procesado.
  - `DatabaseOutageIT` (sus propios contenedores): 1 000 eventos; con unos 200 aplicados,
    PostgreSQL en pausa 15 s; durante la pausa, `health` `DOWN`; al volver, ocupación
    exacta, sin anomalías, DLT vacío, un `WARN` de caída y uno de recuperación.
  - **Mutación:** `isCause` siempre `false` → eventos en el DLT y el test falla; restaurar.
  - Hecho así (2026-10-22): (1) si la conexión muere a mitad de la transacción, el rollback
    también falla y Spring lanza su `TransactionSystemException`: `DatabaseUnavailable` ahora
    reconoce además cualquier `SQLException` con estado SQL `08…` (excepción de conexión).
    (2) La pausa es de 30 s y no de 15: el primer fallo solo aparece a los ~10 s
    (`socketTimeout`), y con 15 s la mutación no llegaba a mandar nada al DLT, así que esa
    aserción no probaba nada. Con 30 s, sin clasificación un evento va al DLT y el test falla.
  - **Verificación:** `./mvnw verify`.

- [x] **T04 — Kafka caído durante el consumo** (plan §3; CA3)
  - `KafkaRestartIT`: Kafka en pausa 10 s a mitad de 1 000 eventos → todos aplicados una vez.
  - Si aparece un fallo, se analiza antes de tocar código (el plan no espera ninguno).
  - **Verificación:** `./mvnw verify`.

- [x] **T05 — Dos instancias del `processor`** (plan §4; CA4)
  - `TwoInstancesIT`: dos aplicaciones sobre los mismos contenedores; las dos reciben
    particiones; se cierra una a mitad de 1 000 eventos → la otra termina; exacto, sin
    anomalías.
  - **Verificación:** `./mvnw verify`, 3 veces seguidas en verde (depende del reparto).
  - Hecho así (2026-10-22): las propiedades de `SpringApplicationBuilder.properties()` son
    valores por defecto y el `server.port: 8081` del `application.yml` les ganaba (la segunda
    instancia no arrancaba). Se pasan como argumentos de línea de comandos, también en
    `ProcessorRestartIT`. 3 `verify` seguidos en verde (171 tests).

- [x] **T06 — Reproceso del DLT** (plan §5; D2, CA5)
  - Paquete `deadletter`: `DeadLetterController` y `DeadLetterReplayer`;
    `spring.mvc.problemdetails.enabled: true` y textos en español.
  - `DeadLetterReplayIT`: zona inexistente → DLT → se añade la zona con SQL → `replay` →
    aplicado y `{"replayed": 1}`; segunda llamada → `{"replayed": 0}`; un evento ya
    aplicado que también está en el DLT → `replay` → no cuenta dos veces.
  - **Mutaciones:** sin copiar la cabecera de versión → el mensaje vuelve al DLT; sin
    confirmar los offsets del grupo → la segunda llamada reenvía otra vez; restaurar.
  - **Verificación:** `./mvnw verify`; manual: `curl -X POST localhost:8081/admin/dead-letters/replay`.
  - Hecho así (2026-10-22): un consumidor creado a mano tiene `enable.auto.commit=true` (solo
    los contenedores de listener lo desactivan): el reproceso lo desactiva y confirma tras
    publicar, y los `TopicProbe` de test tampoco confirman (en el `processor` usaban su grupo).
    Lo destapó la mutación «sin `commitSync`», que al principio no fallaba. Los dos tests
    adelantan el grupo de reproceso al final del DLT antes de empezar, para no depender del
    orden. Manual: un mensaje sin arreglo vuelve al DLT y rebota en cada llamada (esperado).

## Bloque C — API

- [x] **T07 — 503 sin base de datos** (plan §6; D3, CA7)
  - `ApiExceptionHandler`: excepciones de `DatabaseUnavailable` → 503 `ProblemDetail` con
    `Retry-After: 1` y `WARN`.
  - `DatabaseDownIT` (sus propios contenedores): PostgreSQL en pausa → `POST` y
    `GET /zones` → 503, nunca 500; al volver, 202.
  - **Mutación:** sin el manejador → 500; restaurar.
  - **Verificación:** `./mvnw verify`; manual: `docker compose pause postgres` + `curl`.
  - Hecho así (2026-10-22): el 503 tardaba 18,9 s (comprobación de la conexión + 10 s de
    `socketTimeout`); la API usa `socketTimeout: 3` (sus consultas son mínimas) y responde en
    ~3 s en el test, ~5 s a mano. El texto es genérico («La base de datos no está
    disponible»), porque también lo reciben los `GET`. Manual: `POST` y `GET /zones` → 503,
    salud del `processor` → 503 (`DOWN`); al reanudar, 202.

## Bloque D — Cierre

- [ ] **T08 — Demo, documentación y verificación final** (CA8)
  - Demo: simulador a 100 ev/s; a mitad, `docker compose pause postgres` 30 s y `unpause`
    → informe en OK, DLT vacío, los dos `WARN` en el log del `processor`.
  - `docs/adr/0005-fallo-de-infraestructura-frente-a-mensaje-malo.md`.
  - `README.md` (garantías nuevas, reproceso del DLT) y `CLAUDE.md` si cambia algún comando.
  - CA8: clon limpio + `docker compose up -d` + `./mvnw verify`.
  - Tabla de verificación de los CA al final de este fichero; spec y tareas terminadas;
    `MEMORY.md` actualizado (problema abierto de la spec 003 cerrado).
  - **Verificación:** todos los CA con test o verificación manual registrada.

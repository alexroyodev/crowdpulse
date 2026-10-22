# Plan 004 — Fiabilidad ante caídas

- **Estado:** Aprobado (2026-10-22); §8.1: `DatabaseUnavailable` en `shared`; implementado (desviaciones en `tasks.md`, «Hecho así»)
- **Spec:** [`spec.md`](spec.md)
- **Fase:** 3
- **Fecha:** 2026-10-22

Cómo se implementa la spec 004 sobre lo que dejó la 003. No hay módulos nuevos, cambios
en `compose.yaml` ni migraciones. La única dependencia nueva es `spring-tx`/`spring-jdbc`
en `shared` (§8.1, aprobada). Cada decisión no obvia lleva la alternativa descartada.

---

## 1. Idea central
El `processor` ya tiene un único punto donde se decide qué hacer con un fallo: el
`DefaultErrorHandler` de `KafkaConfig`. La spec 004 le añade una pregunta más: **¿es la
base de datos, o es el mensaje?**

```
el listener falla con la excepción ex
  ├─ ex es «base de datos no disponible»      → reintento sin límite, 1 s → 30 s   (D1)
  │     (el offset no avanza: la partición espera; nada va al DLT)
  ├─ ex no se puede arreglar reintentando      → DLT al primer intento             (spec 003)
  └─ cualquier otra                            → 3 intentos, 1 s, y DLT            (spec 003)
```

El resto de la spec son pruebas de lo que ya debería funcionar (Kafka caído, dos
instancias), el endpoint de reproceso (D2) y el 503 de la API (D3).

## 2. Base de datos caída (reglas 1 y 2; CA1, CA2)

### 2.1 Qué cuenta como «base de datos no disponible»
Una clase pura, `DatabaseUnavailable.isCause(Throwable)`, recorre la cadena de causas y
responde `true` si encuentra:

| Excepción | Cuándo aparece |
|-----------|----------------|
| `CannotCreateTransactionException` | `@Transactional` no consigue conexión al empezar |
| `DataAccessResourceFailureException` (y su hija `CannotGetJdbcConnectionException`) | Spring traduce así los errores de conexión de PostgreSQL (estado SQL `08…`) |
| `TransientDataAccessResourceException` | Recurso no disponible de forma pasajera |
| `java.sql.SQLTransientConnectionException` | Hikari no consigue conexión en `connection-timeout` |
| Cualquier `SQLException` con estado SQL `08…` | Añadido en T03: si la conexión muere a mitad de la transacción, el rollback también falla y Spring lanza la `TransactionSystemException` del rollback; solo el estado SQL del driver dice qué pasó |

**Lista cerrada y estrecha, a propósito.** Si un error que no es de conexión se
clasificara como tal, su mensaje se reintentaría para siempre y bloquearía su partición.
Por eso **no** entran `DataIntegrityViolationException` ni las excepciones de SQL en
general. *Descartado:* reintentar sin límite cualquier `DataAccessException`. Un
`CHECK` que falla por un error de programación pararía el `processor` entero.

### 2.2 Reintento sin límite (D1)
```java
handler.setBackOffFunction((record, ex) ->
        DatabaseUnavailable.isCause(ex) ? untilTheDatabaseIsBack() : null);  // null → el de la spec 003

static BackOff untilTheDatabaseIsBack() {
    ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);         // 1, 2, 4, 8, 16…
    backOff.setMaxInterval(30_000);                                          // …30, 30, 30
    return backOff;                                                          // sin maxElapsedTime: sin límite
}
```
- `resetStateOnExceptionChange` ya vale `true`: si el mismo mensaje pasa de fallar por la
  base de datos a fallar por otra cosa, empieza la cuenta de nuevo con el backoff que toque.
- La espera ocurre en el hilo consumidor y el gestor vuelve a la posición del mensaje
  (*seek*): **el offset no avanza** y ningún evento posterior de esa partición se aplica
  antes (regla 2). Como hay un único hilo para las 3 particiones, todo el `processor`
  espera. Es lo que se quiere: sin base de datos no puede aplicar nada.
- Las esperas de 30 s quedan lejos de `max.poll.interval.ms` (5 min), así que Kafka no
  echa al consumidor del grupo mientras espera.

### 2.3 Que un fallo se detecte en segundos, no en minutos
| Propiedad (`processor` y `api`) | Valor | Por qué |
|---------------------------------|-------|---------|
| `spring.datasource.hikari.connection-timeout` | `3000` ms | Por defecto son 30 s: cada intento tardaría 30 s en fallar y el backoff de D1 no se notaría |
| `spring.datasource.hikari.data-source-properties.socketTimeout` | `10` s | Con la base de datos colgada (no caída), una consulta se quedaría esperando sin límite en el socket |

### 2.4 Logs (CA2)
- Un componente pequeño, `DatabaseOutage`, guarda si la base de datos está caída:
  - `failed()` escribe `WARN "Database unavailable: processing paused"` solo la primera
    vez. Lo llama un `RetryListener` del gestor cuando la excepción es de base de datos.
  - `succeeded()` escribe `WARN "Database available again"` con la duración de la caída,
    solo si estaba caída. Lo llama el listener después de cada evento procesado.
- `handler.setLogLevel(DEBUG)`: el gestor deja de escribir un ERROR por cada intento. El
  ERROR del DLT (spec 003) no cambia, porque lo escribe nuestro recuperador.
- **Salud:** Spring Boot ya incluye el indicador `db` en `/actuator/health`. Con la base
  de datos caída, el `processor` responde `DOWN` sin código nuevo; un test lo comprueba.

### 2.5 Cómo se prueba una caída
Con **`docker pause`**, no `stop`: Testcontainers y Docker Compose publican PostgreSQL en
un puerto aleatorio, que puede cambiar al volver a arrancar. Con `pause` el puerto se
conserva, las conexiones se cuelgan y saltan los tiempos de §2.3. Para la demo manual:
`docker compose pause postgres` / `unpause`.

## 3. Kafka caído durante el consumo (CA3)
No se espera código nuevo. El cliente de Kafka se reconecta solo, y si una confirmación
de offset falla durante la caída, el mensaje se vuelve a entregar y la idempotencia por
`eventId` lo absorbe (ADR 0004). El test lo demuestra: el broker se pausa 10 s a mitad de
1 000 eventos, menos que `session.timeout.ms` (45 s), así que el grupo no se rehace.

## 4. Dos instancias del `processor` (regla 4, CA4)
Tampoco se espera código nuevo: mismo `group-id`, 3 particiones; Kafka las reparte entre
las dos instancias.
- Al parar una instancia (cierre ordenado), Spring confirma el offset de lo procesado
  antes de soltar sus particiones, y la otra sigue desde ahí.
- Si dos instancias llegaran a procesar el mismo mensaje durante el reparto, el
  `FOR UPDATE` por zona y el `eventId` impiden que cuente dos veces (ADR 0002, ADR 0004).
- El test usa el patrón de la spec 003 (`ENTRY`, `ENTRY`, `EXIT`… por zona): cualquier
  desorden produciría una anomalía.

## 5. Reproceso del DLT (D2, regla 5, CA5)

`POST /admin/dead-letters/replay` en el `processor` (paquete nuevo `deadletter`):

| Clase | Qué hace |
|-------|----------|
| `DeadLetterController` | El endpoint; responde `200 {"replayed": n}`, o 503 `ProblemDetail` si Kafka no responde |
| `DeadLetterReplayer` | Lee el DLT con su propio grupo, `crowdpulse-dlt-replay`, desde su último offset hasta el final que había al empezar; publica cada mensaje en `crowdpulse.sensor-events` con la **misma clave, el mismo valor y sus cabeceras originales sin las `kafka_dlt-*`** (la de versión se conserva); espera la confirmación de cada uno y al final confirma los offsets del grupo |

- **Cada mensaje se reprocesa una vez:** el grupo propio recuerda hasta dónde llegó.
  Llamar dos veces seguidas reenvía 0 la segunda.
- **Confirmar después de publicar**, igual que el `processor`: si algo falla a mitad, lo
  ya publicado se volverá a publicar, y eso es un duplicado inofensivo (regla 5).
- **Si la causa no se arregló, el mensaje vuelve al DLT.** No hay bucle automático: solo
  se reprocesa cuando alguien llama al endpoint.
- Errores del endpoint en `ProblemDetail` (`spring.mvc.problemdetails.enabled: true`),
  con textos en español.
- **Sin autenticación (D2).** El puerto 8081 solo debe estar accesible en local. Queda
  como riesgo en §8.

*Descartado:* que el endpoint reciba qué mensajes reprocesar (por offset o por
`eventId`). Ahora no hace falta: tras arreglar la causa se reprocesa todo lo pendiente.

## 6. API con la base de datos caída (D3, CA7)
`ApiExceptionHandler` atiende las mismas excepciones de §2.1, reutilizando la misma lista
(copia de la clase en la API, o la clase en `shared`; ver §8), y responde **503**
`ProblemDetail`: «No se puede consultar el catálogo de zonas; reinténtalo.», con
`Retry-After: 1` y un log `WARN`. Vale para el `POST` y también para los `GET`, que hoy
responden 500 por el mismo motivo.

## 7. Tests

| Test | Módulo | Criterio |
|------|--------|----------|
| `DatabaseUnavailableTest` (unitario) | `processor` | Cada excepción de §2.1, también envuelta (`ListenerExecutionFailedException` → … → causa); `DataIntegrityViolationException`, `JacksonException` y `UnknownZoneException` no cuentan |
| `DatabaseOutageIT` | `processor` | CA1 y CA2, con sus propios contenedores: 1 000 eventos en el topic; con unos 200 aplicados, PostgreSQL en pausa 15 s; durante la pausa, `health` `DOWN`; al volver, ocupación exacta, ninguna anomalía, DLT vacío; un solo `WARN` de caída y uno de recuperación |
| `KafkaRestartIT` | `processor` | CA3: Kafka en pausa 10 s a mitad de 1 000 eventos → todos aplicados una vez |
| `TwoInstancesIT` | `processor` | CA4: dos aplicaciones (`SpringApplicationBuilder`) sobre los mismos contenedores; cada una recibe particiones; se cierra una a mitad → la otra termina; exacto, sin anomalías |
| `DeadLetterReplayIT` | `processor` | CA5: un mensaje de una zona que no existe va al DLT → se añade la zona (SQL) → `replay` → aplicado, `{"replayed": 1}`; una segunda llamada → `{"replayed": 0}`; un evento ya aplicado que también está en el DLT → `replay` → no cuenta dos veces |
| `DeadLetterIT` | `processor` | CA6: sin cambios, debe seguir pasando |
| `DatabaseDownIT` | `api` | CA7: PostgreSQL en pausa → `POST` y `GET /zones` responden 503 `ProblemDetail`, nunca 500; al volver, 202 |

**Pruebas de mutación** (principio 2):
| Se quita | Debe fallar |
|----------|-------------|
| La clasificación (`isCause` devuelve siempre `false`) | `DatabaseOutageIT`: los eventos de la caída acaban en el DLT |
| Copiar la cabecera de versión al reprocesar | `DeadLetterReplayIT`: el mensaje vuelve al DLT |
| Confirmar los offsets del grupo de reproceso | `DeadLetterReplayIT`: la segunda llamada reenvía otra vez |
| El manejador 503 de la API | `DatabaseDownIT`: 500 |

**Sin mutación propia:** CA3 y CA4 demuestran que los mecanismos de la spec 003
(offset tras el commit, idempotencia, bloqueo por zona) también cubren estos fallos.
Esos mecanismos ya se validaron quitándolos (`AtLeastOnceIT`, `ConcurrentIngestionIT`).
Quitarlos aquí no garantiza un fallo determinista, porque depende de cuándo cae cada
mensaje. Lo digo para que no parezca que estos tests prueban más de lo que prueban.

**Manual:** demo con compose: simulador a 100 ev/s; a mitad, `docker compose pause
postgres` 30 s y `unpause` → informe en OK y DLT vacío. Después, `curl -X POST
localhost:8081/admin/dead-letters/replay`.

## 8. Riesgos y puntos a revisar
1. **¿Dónde vive `DatabaseUnavailable`?** La necesitan el `processor` (§2) y la API (§6).
   Propongo ponerla en `shared`: son dos usos reales (principio 1). La contrapartida es
   que `shared` pasaría a depender de `spring-tx`/`spring-jdbc` para conocer esas
   excepciones; las dos aplicaciones ya las traen, pero `shared` dejaría de ser un jar
   casi vacío. La alternativa es una copia de unas 20 líneas en cada aplicación.
   **Decidido por el autor (2026-10-22): en `shared`.**
2. **Una caída de la base de datos para todo el `processor`,** no solo la zona afectada:
   hay un único hilo consumidor. Es intencionado (sin base de datos no se puede aplicar
   nada), pero conviene saberlo.
3. **Un error mal clasificado bloquearía una partición para siempre.** Por eso la lista de
   §2.1 es corta y tiene su test unitario; el `WARN` de caída deja ver si pasa.
4. **El endpoint de reproceso no tiene autenticación (D2).** Cualquiera que llegue al 8081
   puede reprocesar el DLT; como el reproceso es idempotente, lo peor sería volver a
   mandar al DLT mensajes que siguen siendo malos.
5. **Coste de los tests:** cuatro tests de integración con sus propios contenedores o dos
   aplicaciones. Hay que ejecutar `verify` de uno en uno (ver `CLAUDE.md` › Notas de
   Windows).
6. **ADR propuesto:** `docs/adr/0005-fallo-de-infraestructura-frente-a-mensaje-malo.md`,
   con la decisión de §1–§2 y lo que se descartó.

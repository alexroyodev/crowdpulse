# ADR 0005 — Fallo de infraestructura frente a mensaje malo

- **Estado:** Aceptada
- **Fecha:** 2026-10-22
- **Spec:** [004 — Fiabilidad ante caídas](../../specs/004-fiabilidad-ante-caidas/spec.md)

## Contexto
Con la spec 003, el `processor` reintentaba 3 veces en 2 s cualquier fallo y después
mandaba el mensaje al DLT. Si PostgreSQL tardaba más en volver, eventos correctos
acababan en el DLT y la ocupación quedaba mal. Un fallo de infraestructura y un mensaje
malo recibían el mismo trato, pero no tienen la misma solución:

- un **mensaje malo** no se arregla esperando: tiene que apartarse para que su zona siga;
- un **fallo de la base de datos** no es culpa del mensaje: apartarlo sería perderlo.

La spec 004 (D1) pide esperar sin límite a la base de datos, y que el DLT reciba solo lo
que de verdad no se puede procesar.

## Decisión
1. **Una sola pregunta, en un solo sitio:** el `DefaultErrorHandler` de `KafkaConfig`
   pregunta a `DatabaseUnavailable.isCause(ex)` (en `shared`) si el fallo es de la base de
   datos.
2. **Si lo es: reintento sin límite** (`setBackOffFunction`, `ExponentialBackOff` de 1 s
   a 30 s). El offset no avanza, así que ningún evento posterior de la partición se aplica
   antes y nada va al DLT. Si no lo es, se aplica la política de la spec 003.
3. **Lista corta de causas:** `CannotCreateTransactionException`,
   `DataAccessResourceFailureException`, `TransientDataAccessResourceException`,
   `SQLTransientConnectionException` y cualquier `SQLException` con estado SQL de clase
   `08` (excepción de conexión, según el estándar SQL).
4. **Detectarlo en segundos:** Hikari se rinde a los 3 s sin conexión y el driver corta
   una consulta colgada a los 10 s (3 s en la API, que solo hace consultas mínimas).
5. **Logs y salud:** un `WARN` al empezar la caída y otro al terminar (`DatabaseOutage`),
   no un ERROR por intento. El indicador `db` de Spring Boot pone `/actuator/health` en
   `DOWN`.
6. **La API responde 503** cuando no puede consultar el catálogo (D3), con el mismo
   clasificador.
7. **Reproceso del DLT** bajo demanda (`POST /admin/dead-letters/replay`, D2): con su propio
   grupo de consumidores, confirmando después de publicar.

## Alternativas descartadas
- **Reintentar sin límite cualquier `DataAccessException`.** Un `CHECK` que falla por un
  error de programación bloquearía su partición para siempre.
- **Pausar el contenedor del listener mientras la base de datos esté caída** y reanudarlo
  con un comprobador aparte. Es más preciso (no gasta intentos), pero añade un hilo y un
  estado que coordinar. Con un único hilo consumidor, esperar en el gestor de errores da
  el mismo resultado.
- **Un límite largo (p. ej. 10 min) y después al DLT.** Rechazado por el autor (D1): un
  evento correcto nunca debe acabar en el DLT por una caída.

## Evidencia
- `DatabaseOutageIT` (CA1, CA2): PostgreSQL en pausa 30 s a mitad de 1 000 eventos. Al
  volver, la ocupación es exacta, no hay anomalías, el DLT está vacío y hay un `WARN` de
  caída y otro de recuperación; durante la pausa, `health` está en `DOWN`. **Sin
  clasificador, un evento acaba en el DLT y el test falla.**
- El test destapó que, si la conexión muere a mitad de la transacción, el rollback también
  falla y Spring lanza la `TransactionSystemException` del rollback: por eso se reconoce
  el estado SQL `08…`. Con 15 s de pausa la mutación no llegaba a mandar nada al DLT; por
  eso la pausa es de 30 s.
- `KafkaRestartIT` (CA3) y `TwoInstancesIT` (CA4): no necesitaron código, y no tienen
  mutación propia (se apoyan en las de la spec 003).
- `DeadLetterReplayIT` (CA5): sin copiar las cabeceras, o sin confirmar los offsets del
  reproceso, falla.
- `DatabaseDownIT` (CA7): sin el manejador, la API responde 500.
- Demo: 6 000 eventos esperando en Kafka y PostgreSQL en pausa 30 s con 227 aplicados.
  Al volver, las cuatro zonas cuadran con lo esperado y el DLT no recibe nada.

## Consecuencias
- ✅ Una caída de la base de datos retrasa el procesamiento, pero no lo estropea.
- ⚠️ **Mientras la base de datos esté caída, todo el `processor` espera** (un solo hilo
  consumidor), y la API rechaza los eventos nuevos con 503 (D3): el sensor debe reintentar.
- ⚠️ **Una excepción mal clasificada bloquearía una partición para siempre.** La lista es
  corta y tiene su test unitario; el `WARN` de caída deja verlo.
- ⚠️ El `WARN` mide la caída desde que se detecta, no desde que empieza: una consulta en
  curso tarda hasta 10 s en fallar.
- ⚠️ El endpoint de reproceso no tiene autenticación, y un mensaje sin arreglo vuelve al
  DLT cada vez que se reprocesa.

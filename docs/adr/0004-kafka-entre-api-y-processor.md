# ADR 0004 — Kafka entre la API y el `processor`: offset después del commit

- **Estado:** Aceptada
- **Fecha:** 2026-10-22
- **Spec:** [003 — Procesamiento asíncrono](../../specs/003-procesamiento-asincrono/spec.md)

## Contexto
La spec 003 separa la ingesta del procesamiento. La API valida, publica en Kafka y
responde 202, y un servicio nuevo, `processor`, aplica las reglas de las specs 001 y 002.
Kafka no hace fiable el sistema por sí solo. La spec exige:

- nunca un 202 sin el evento en Kafka (regla 3);
- orden por zona (regla 2);
- cada evento aceptado se procesa al menos una vez y no cuenta dos veces aunque llegue
  repetido (regla 4, CA6, CA7);
- los mensajes que no se pueden procesar no se pierden ni bloquean su zona (regla 7).

## Decisión
1. **Clave del mensaje = `zoneId`**, topic con 3 particiones: los eventos de una zona van
   a la misma partición y un único hilo consumidor los aplica en orden.
2. **La API espera la confirmación del broker** (`acks=all`, `send().get()`, unos 3 s
   como máximo). Si no llega, responde **503** con `Retry-After`; el sensor reintenta y
   el reintento es un duplicado inofensivo.
3. **El offset se confirma después del commit de PostgreSQL** (`ack-mode: record`): un
   mensaje = una transacción = un offset. Si el `processor` cae antes de confirmar el
   offset, el mensaje vuelve a llegar.
4. **Idempotencia en el consumidor:** el `eventId` es la clave primaria de
   `sensor_event`, y guardar el evento y actualizar la ocupación van en la misma
   transacción (ADR 0002, ADR 0003). Una segunda entrega no cambia nada.
5. **Errores:** 3 intentos con 1 s de espera y después el topic
   `crowdpulse.sensor-events.dlt`, con log `ERROR` y su `eventId`. Lo que reintentar no
   arregla (JSON roto, campos que faltan, versión desconocida, zona inexistente) va al
   DLT al primer intento.
6. **Mensaje v1** = el evento + `receivedAt`, en JSON convertido a mano con el `JsonMapper`
   de Spring Boot. La versión va en la cabecera `crowdpulse-message-version`. El record
   `SensorEventMessage` vive en el módulo `shared`, que comparten las dos aplicaciones.

## Alternativas descartadas
- **Patrón *outbox*** (la API guarda en PostgreSQL y otro proceso publica). Es la forma de
  no depender de Kafka para responder, pero añade una tabla, un *poller* y otro sitio
  donde perder o duplicar. La spec eligió el 503 (D3).
- **Transacciones de Kafka / *exactly-once*.** Fuera del alcance de la spec. La
  idempotencia por `eventId` da el mismo resultado visible con menos piezas.
- **`ack-mode: batch`** (el valor por defecto). También es correcto, pero repite más
  mensajes tras un reinicio y se pierde la explicación «un mensaje, un offset».
- ***Retry topics* (`@RetryableTopic`).** No bloquean la partición, pero el evento que se
  reintenta se desordena respecto a los siguientes de su zona.
- **`JacksonJsonSerializer` de Spring Kafka.** Un mensaje imposible de deserializar falla
  antes del listener y obliga a añadir un `ErrorHandlingDeserializer`.

## Evidencia
- `AtLeastOnceIT` (CA7). El primer intento falla y el contenedor del listener se para y
  se arranca otra vez, así que el consumidor nuevo parte del offset confirmado, como tras
  una caída real:
  - Con la caída antes del commit, el evento vuelve y se aplica. Con el offset confirmado
    antes de procesar, **se pierde**.
  - Con la caída después del commit (lanzada en `afterCommit`), el evento vuelve y cuenta
    una vez. Sin detectar duplicados, **cuenta dos veces**.
  - El espía de Mockito queda dentro del proxy transaccional: una excepción lanzada desde
    él deshace la transacción y no simula «después del commit». Lo destapó la prueba de
    mutación.
- `ProcessorRestartIT` (CA6): 1 000 eventos, el `processor` se cierra a mitad y se arranca
  de nuevo. La ocupación es exacta y no hay anomalías.
- `EventPublishingIT` (CA5): con `eventId` como clave, los eventos de una zona se reparten
  entre particiones y el test falla.
- `KafkaOutageIT` (CA2): con el broker en pausa, la API responde 503 en unos 3 s. Si
  respondiera 202 sin esperar al broker, el test falla.
- `DeadLetterIT` (CA3b): sin el recuperador del DLT fallan 4 de 5 casos.
- Demo (CA9): 6 000 eventos a 100 ev/s con un 5 % de duplicados. Latencia
  `processed_at − received_at`: p50 22 ms, p99 58 ms.

## Consecuencias
- ✅ Una caída de PostgreSQL ya no afecta a los sensores: la API solo depende de Kafka y
  de leer el catálogo.
- ✅ Las garantías de las specs 001 y 002 no cambian: el código que las da se movió entero
  al `processor`.
- ⚠️ **Consistencia eventual:** justo después de un 202, `GET /zones` puede no reflejar
  aún el evento.
- ⚠️ **Con PostgreSQL caído más de ~3 s, los eventos acaban en el DLT** y la ocupación
  queda mal hasta reprocesarlos. Distinguir «base de datos caída» de «mensaje malo» queda
  para la fase 3.
- ⚠️ Kafka en local usa el puerto fijo 9092: Spring Boot 4.1 no lo reconoce en Docker
  Compose.

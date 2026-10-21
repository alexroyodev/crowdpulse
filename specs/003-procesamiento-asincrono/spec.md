# Spec 003 — Procesamiento asíncrono con Kafka

- **Estado:** Borrador: decisiones D1–D4 resueltas (2026-10-21), pendiente de aprobación
- **Fase:** 2
- **Fecha:** 2026-10-21
- **Depende de:** [spec 001](../001-ingesta-mvp/spec.md), [spec 002](../002-alertas-umbrales/spec.md)

## Contexto y objetivo
Hoy la API procesa cada evento dentro de la propia petición HTTP: abre una transacción,
bloquea la fila de la zona, actualiza ocupación y alertas, y solo entonces responde.
Eso tiene dos costes:

- **El sensor espera a la base de datos.** Si PostgreSQL va lento o se cae, el sensor
  recibe errores o *timeouts*, aunque el evento sea correcto.
- **La ingesta y el procesamiento escalan juntos.** Un pico de entradas en una zona se
  traduce en peticiones HTTP esperando el bloqueo de esa zona.

Esta spec separa las dos cosas. La API valida el evento, lo publica en Kafka y responde.
Un servicio nuevo, `processor`, consume los eventos y aplica las reglas de las specs
001 y 002. El contrato con los sensores no cambia: por eso `POST /events` responde 202
desde la spec 001.

Objetivo de aprendizaje: particionado por zona (orden), entrega *at-least-once* y
confirmación del *offset* después de persistir, demostrado con tests.

## Alcance

### Incluido
- Un topic de Kafka con los eventos aceptados, con clave `zoneId`.
- La API publica cada evento válido y responde 202 solo cuando Kafka lo ha guardado.
- Un servicio nuevo, `processor`, que consume el topic y aplica las reglas de las
  specs 001 y 002: ocupación, duplicados, anomalías y alertas.
- Instante de procesamiento (`processedAt`) por evento, para medir la latencia entre
  la API y el procesamiento.
- La API sigue sirviendo las consultas (`GET /zones`, `GET /alerts`), que leen lo que
  escribe el `processor`.
- Kafka en `compose.yaml` y en los tests de integración (Testcontainers).
- El simulador espera a que se procesen los eventos antes de comprobar CA10.

### Fuera de alcance
- Fase 3 de la constitución: escenarios de caída (base de datos caída a mitad de un
  lote, broker caído durante el consumo, particiones reasignadas). Ver D1 para los
  mensajes que no se pueden procesar.
- Exactamente una vez (*exactly-once*), transacciones de Kafka, *schema registry*, Avro.
- Varias instancias del `processor` a la vez (el diseño lo permite, pero no se prueba).
- Métricas y trazas (fase 4).

## Contrato

### Sensores → API (sin cambios)
El evento y las respuestas de `POST /api/v1/events` son los de la spec 001. Cambia lo que
significa **202**: el evento está guardado en Kafka y se procesará, no que ya se haya
aplicado.

| Respuesta | Cuándo |
|-----------|--------|
| 202 | El evento es válido y Kafka ha confirmado que lo tiene |
| 400 | Igual que en la spec 001 (validación en la API, antes de publicar) |
| 422 | `zoneId` no existe: lo comprueba la API con el catálogo, antes de publicar (D2) |
| 503 | Nuevo: Kafka no confirma el evento a tiempo; `ProblemDetail`, el sensor reintenta (D3) |

### API → `processor` (mensaje interno, versión 1)
- Topic `crowdpulse.sensor-events`, clave = `zoneId`.
- Valor: el evento tal como llegó más `receivedAt`, que añade la API. Es la versión 1
  del mensaje; cambios incompatibles irán en una versión nueva.

### Consultas
`GET /api/v1/zones`, `GET /api/v1/zones/{zoneId}` y `GET /api/v1/alerts` no cambian de
formato. Reflejan los eventos ya procesados: un evento recién aceptado puede tardar un
momento en verse (consistencia eventual).

## Reglas de negocio
1. Las reglas de las specs 001 y 002 siguen valiendo; las aplica el `processor` en vez
   de la API.
2. **Orden por zona:** los eventos de una zona se procesan en el orden en que los aceptó
   la API (misma clave → misma partición). Entre zonas distintas no hay orden.
3. **Nunca un 202 sin el evento en Kafka.** Si la API no consigue publicarlo, no responde
   202.
4. **At-least-once:** un evento aceptado se procesa al menos una vez. El `processor`
   confirma el *offset* **después** de guardar en la base de datos, nunca antes. Si se
   reinicia entre una cosa y otra, el evento llega otra vez y, como su `eventId` ya está
   guardado, no cuenta dos veces.
5. **Duplicados:** un `eventId` repetido se publica igual (la API no lo puede saber) y el
   `processor` lo ignora, como en la spec 001.
6. **Tres tiempos por evento:** `occurredAt` (el sensor), `receivedAt` (la API) y
   `processedAt` (el `processor`). La latencia del sistema es `processedAt − receivedAt`.
7. **Mensajes que no se pueden procesar** (D1): unos pocos reintentos y después al topic
   `crowdpulse.sensor-events.dlt`, con log `ERROR` y su `eventId`. Nunca se descartan ni
   bloquean su zona para siempre.

## Simulador
Al terminar de enviar, el simulador espera a que la API refleje los eventos antes de
comprobar CA10: repite la consulta hasta que el cambio de ocupación coincide con el
esperado o pasan 30 s. El informe muestra cuánto tardó en cuadrar (D4).

## Criterios de aceptación
- **CA1** — Un `ENTRY` válido recibe 202 y, en menos de 5 s, `GET /api/v1/zones/{zoneId}`
  refleja el cambio. El evento guardado tiene `receivedAt ≤ processedAt`.
- **CA2** — Con Kafka parado, `POST /api/v1/events` no responde 202 y no se pierde nada:
  al volver Kafka, los eventos que recibieron 202 antes de la caída están procesados.
  (D3: responde 503.)
- **CA3** — Un evento inválido (400) o de una zona inexistente (422) no llega al topic.
- **CA3b** — Un mensaje que el `processor` no consigue procesar acaba en el DLT, y los
  siguientes eventos de su zona se siguen procesando.
- **CA4** — El mismo evento enviado dos veces: dos 202, y la ocupación cambia una sola vez.
- **CA5** — Todos los eventos de una zona van a la misma partición, y se aplican en el
  orden en que se publicaron.
- **CA6** — **Reinicio del `processor` sin perder eventos:** se envían 1 000 eventos, se
  para el `processor` a mitad y se arranca otra vez. Al final la ocupación es la
  correcta: ni eventos perdidos ni contados dos veces.
- **CA7** — Si el `processor` cae después de guardar en la base de datos y antes de
  confirmar el *offset*, el evento se vuelve a entregar y no cuenta dos veces.
- **CA8** — Los criterios de las specs 001 y 002 que tienen sentido de extremo a extremo
  se cumplen por el camino asíncrono: 1 000 `ENTRY` concurrentes → 1 000; histéresis y
  cambios de nivel de alerta; `EXIT` con la zona vacía → anomalía.
- **CA9** — Demo: el simulador a 100 ev/s durante 60 s con 5 % de duplicados termina en
  OK, y la latencia `processedAt − receivedAt` queda registrada.
- **CA10** — `docker compose up -d` levanta PostgreSQL y Kafka, y `./mvnw verify` pasa en
  verde desde un clon limpio.

## Decisiones del autor (2026-10-21)
| Id | Decisión | Elegida (propuesta aceptada) |
|----|----------|-----------|
| D1 | Mensajes que no se pueden procesar. `CLAUDE.md` dice que van a un dead-letter topic; la constitución pone el DLT en la fase 3 | DLT mínimo en esta fase: unos pocos reintentos y luego al topic `crowdpulse.sensor-events.dlt`, registrado en el log. Los escenarios de caída siguen en la fase 3 |
| D2 | ¿Quién responde 422 a una zona inexistente? | La API, consultando el catálogo de zonas antes de publicar: el contrato no cambia |
| D3 | ¿Qué responde la API si Kafka no confirma? | 503 `ProblemDetail`; el sensor reintenta (los duplicados ya son inofensivos) |
| D4 | Espera del simulador antes de comprobar CA10 | Consultar hasta que cuadre, con un máximo de 30 s |

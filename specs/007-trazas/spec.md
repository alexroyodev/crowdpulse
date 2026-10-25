# Spec 007 — Trazas

- **Estado:** Aprobada (2026-10-25); D1 y D2 con las opciones recomendadas; ajustes del plan,
  sección 0
- **Fase:** 4
- **Fecha:** 2026-10-25
- **Depende de:** [spec 006](../006-metricas/spec.md)

## Contexto y objetivo
Las métricas de la spec 006 dicen **cuántos** eventos van bien o mal y **cuánto** tardan
de media, pero no qué le pasó a **uno** en concreto. Hoy, para seguir un evento hay que
buscar su `eventId` en los logs de la `api` y luego en los del `processor`, y reconstruir
a mano el orden y los tiempos. El principio 9 pide que en la fase 4 eso se convierta en
trazas; esta spec es la segunda de las tres de la fase (métricas → trazas → dashboard).

Objetivo: que, dado un `eventId`, se pueda abrir una sola traza que muestre su recorrido
completo —petición a la `api`, publicación en Kafka, espera en el topic, procesamiento en
el `processor` y resultado— con el tiempo de cada tramo, y que los logs de ese recorrido
lleven el mismo identificador de traza.

Preguntas que una traza tiene que contestar:
1. ¿Por dónde pasó este evento y cuánto tardó cada tramo (API, espera en Kafka, proceso)?
2. ¿Qué resultado tuvo: aplicado, duplicado, `EXIT` en 0, rechazado o DLT?
3. Si hubo reintentos (BD caída, fallos pasajeros), ¿cuántos y cuánto se esperó?
4. ¿Qué líneas de log se escribieron durante ese recorrido, en los dos servicios?

## Alcance

### Incluido
- `api` y `processor` generan trazas y las envían a un backend de trazas (D1).
- El contexto de la traza viaja de la `api` al `processor` con cada mensaje de Kafka, de
  modo que el recorrido de un evento es **una** traza, no dos.
- Atributos de negocio en los tramos (ver Contrato), buscables en el backend.
- El identificador de traza en los logs estructurados de los dos servicios.
- El backend de trazas en `compose.yaml`, con su interfaz solo en `127.0.0.1` (como
  Prometheus y Grafana), sin pasos a mano.
- Una demo reproducible: simulador con duplicados + pausa de PostgreSQL, vista en trazas.

### Fuera de alcance
- Dashboard en React: spec siguiente de la fase 4.
- Trazas desde el simulador: un sensor real no las enviaría; la traza empieza en la `api`.
  Si una petición ya trae una cabecera `traceparent` (W3C), se respeta, pero no se exige.
- Envío de logs o métricas por OpenTelemetry: los logs siguen en consola y las métricas en
  Prometheus (spec 006).
- Enlaces de métricas a trazas (*exemplars*) y vistas de trazas dentro de Grafana.
- Retención larga, alta disponibilidad o autenticación del backend de trazas.

## Reglas de negocio
1. **Las trazas observan; no deciden.** Si el backend de trazas no está o no responde, la
   `api` acepta y el `processor` procesa exactamente igual: las trazas se pierden, los
   eventos no. Nunca se responde un error ni se reintenta un mensaje por culpa de las trazas.
2. **Un mensaje sin contexto de traza se procesa igual.** Los mensajes publicados antes de
   esta spec, los que vengan de otros productores y los que se reprocesen del DLT no tienen
   por qué llevarlo; en ese caso el `processor` empieza una traza nueva. El cuerpo del mensaje
   (`SensorEventMessage` v1) no cambia: el contexto va en las cabeceras de Kafka.
3. **Cada intento se ve.** Si un mensaje se procesa varias veces (reintento tras un fallo,
   espera a que vuelva la BD), cada intento es un tramo de la misma traza y los fallidos se
   marcan como error con su causa. Solo el intento confirmado lleva el resultado final.
4. **Los tiempos de los tramos son tiempo del sistema** (principio 4). La traza mide cuándo
   se procesó cada paso; `occurredAt` se adjunta como atributo, no se usa como inicio de
   ningún tramo.
5. **En las trazas sí va el `eventId`.** A diferencia de las etiquetas de métricas (spec 006,
   regla 5), un atributo de traza puede ser único por evento: es lo que permite buscarla.
   Nunca se adjuntan datos que no estén ya en el evento ni valores sensibles.
6. **Sin ruido de infraestructura.** Las consultas de salud y de métricas (`/actuator/*`,
   Prometheus lee cada pocos segundos) no generan trazas.
7. **Un evento repetido es otra traza.** Un duplicado o un reproceso del DLT es un recorrido
   nuevo con su propia traza; buscar por `eventId` devuelve todas las de ese evento. Un
   reproceso es una sola traza (la de su petición) con todos los mensajes que republica.

## Contrato

### Tramos y atributos
Nombres orientativos; los definitivos, en el plan. Lo que se fija aquí es qué tramos existen
y qué atributos llevan.

| Tramo | Servicio | Atributos propios |
|---|---|---|
| Petición `POST /api/v1/events` | `api` | `crowdpulse.event.id`, `crowdpulse.zone.id`, `crowdpulse.event.type`; código HTTP |
| Publicación en `sensor-events` | `api` | los de Kafka (topic, partición) |
| Recepción y proceso del mensaje (uno por intento) | `processor` | `crowdpulse.event.id`, `crowdpulse.zone.id`, `crowdpulse.event.type`, `crowdpulse.event.occurred_at`, `crowdpulse.outcome` |
| Envío al DLT | `processor` | `crowdpulse.dead_letter.reason` = `content` \| `other` |

- `crowdpulse.outcome` = `applied` \| `duplicate` \| `exit_at_zero` \| `dead_lettered`
  (mismos valores que la métrica de la spec 006, más el DLT).
- Una petición rechazada (400, 422, 503) también deja su traza, con el código HTTP. Los
  atributos de evento solo están si la petición pasó la validación (422 por zona desconocida,
  503); un 400 no los lleva (plan, sección 0).
- Propagación entre servicios con el estándar W3C Trace Context (cabecera `traceparent`),
  en HTTP y en las cabeceras de los mensajes de Kafka.

### Logs
Cada línea de log escrita dentro de una traza lleva `traceId` y `spanId`, los nombres con los
que Spring Boot los pone en el MDC y que su formato ECS copia tal cual (plan, sección 0). Las
que se escriben fuera de una traza no los llevan.

### Backend y muestreo
- Interfaz del backend de trazas en `http://localhost:{puerto}`, solo en `127.0.0.1` (D1).
- Muestreo: en local se guardan **todas** las trazas (a 100 ev/s el backend lo aguanta y la
  demo necesita encontrar cualquier `eventId`). La proporción se puede cambiar por
  configuración sin tocar código.

## Criterios de aceptación
- **CA1** — Tras un `POST` aceptado, existe una traza con el tramo de la `api`, el de la
  publicación y el del `processor`, con el mismo identificador de traza y el `eventId` como
  atributo. Si se quita la propagación del contexto en Kafka, el test falla: salen dos
  trazas separadas (principio 2).
- **CA2** — El tramo del `processor` lleva el `outcome` correcto para un evento nuevo, un
  duplicado y un `EXIT` en 0.
- **CA3** — Un mensaje sin cabecera de traza (publicado a mano) se procesa y deja una traza
  nueva en el `processor`, con el `eventId`.
- **CA4** — Con PostgreSQL en pausa, un evento ya publicado deja una traza con varios
  intentos fallidos (marcados como error) y, al volver la BD, uno final confirmado.
- **CA5** — Un mensaje que va al DLT deja en su traza el envío al DLT con su motivo.
- **CA6** — Con el backend de trazas parado, la `api` acepta (202) y el `processor` aplica
  los eventos igual que con él en marcha; ninguno va al DLT por esa causa.
- **CA7** — Los logs de la `api` y del `processor` escritos durante el recorrido de un
  evento llevan el mismo `trace.id`, y es el de la traza del backend.
- **CA8** — `/actuator/health` y `/actuator/prometheus` no generan trazas.
- **CA9** — `docker compose up -d` levanta el backend de trazas; con la `api`, el `processor`
  y el simulador en marcha, en su interfaz se encuentra la traza de un `eventId` sacado de
  los logs, sin pasos a mano.
- **CA10** — Demo: simulador a 100 ev/s durante 60 s con duplicados y PostgreSQL en pausa
  30 s → se encuentra la traza de un duplicado (`outcome=duplicate`) y la de un evento que
  esperó a la BD (intentos fallidos y luego aplicado), y se lee en ella cuánto esperó.
- **CA11** — `./mvnw verify` (por módulos) pasa en verde desde un clon limpio.

## Decisiones
- **D1 — Resuelta: opción a)** (2026-10-25).
  **D1 — Qué backend de trazas.** Tiene que recibir trazas por OTLP (el protocolo de
  OpenTelemetry), correr en `compose.yaml` y permitir buscar por atributo (`eventId`).
  - **a) (Recomendada)** Jaeger: un solo contenedor sin fichero de configuración, recibe
    OTLP y trae su propia interfaz con búsqueda por atributo. Lo más simple de levantar y
    de explicar.
  - b) Grafana Tempo: se integra con el Grafana de la spec 006 (todo en una interfaz), pero
    necesita su fichero de configuración y una fuente de datos más, y la búsqueda se hace
    con su lenguaje de consultas (TraceQL).
  - c) Un OpenTelemetry Collector delante de cualquiera de los dos: permite cambiar de
    backend sin tocar las aplicaciones, pero añade un contenedor y una configuración que
    hoy no resuelven ningún problema (principio 1: sin abstracciones «por si acaso»).
- **D2 — Resuelta: opción a)** (2026-10-25).
  **D2 — ¿Tramos de base de datos?** Ver cada consulta SQL dentro del tramo del
  `processor` (bloqueo de zona, inserción del evento, alerta) ayudaría a ver dónde se va el
  tiempo, pero Spring Boot no lo hace solo: requiere una dependencia más que envuelva el
  `DataSource`.
  - **a) (Recomendada)** No en esta spec: el tramo del `processor` ya muestra el tiempo total
    de la transacción y los reintentos; si un caso real lo pide, se añade en otra.
  - b) Sí: un tramo por consulta, con la sentencia (sin valores).

## Notas para el autor
- Añade dependencias (soporte de OpenTelemetry de Spring Boot) y modifica `compose.yaml`: aprobar esta spec cuenta como el «pregunta antes»
  de `CLAUDE.md` para ambas cosas.
- **Contrato del evento:** el cuerpo del mensaje no cambia; se añade una cabecera de Kafka
  opcional (`traceparent`). Los productores existentes siguen valiendo (regla 2), así que no
  se versiona `SensorEventMessage` (confirmado con la aprobación, 2026-10-25).
- Versiones exactas de la imagen del backend y de las dependencias, en el plan.
